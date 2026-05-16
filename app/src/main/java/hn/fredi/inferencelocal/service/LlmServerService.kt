package hn.fredi.inferencelocal.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import hn.fredi.inferencelocal.MainActivity
import hn.fredi.inferencelocal.api.LlmEngineKey
import hn.fredi.inferencelocal.api.ModelRegistryKey
import hn.fredi.inferencelocal.api.engine.LlmEngine
import hn.fredi.inferencelocal.api.ollamaApiModule
import hn.fredi.inferencelocal.api.registry.ModelRegistry
import hn.fredi.inferencelocal.R
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────────────────────────────────────
// Estados del servicio (emitidos via Broadcast hacia la UI)
// ─────────────────────────────────────────────────────────────────────────────

sealed class ServerState {
    object Idle                                         : ServerState()
    object LoadingModel                                 : ServerState()
    object StartingServer                               : ServerState()
    data class Running(val port: Int, val modelName: String) : ServerState()
    data class Error(val message: String)               : ServerState()
    object Stopping                                     : ServerState()
    object Stopped                                      : ServerState()
}

// ─────────────────────────────────────────────────────────────────────────────
// LlmServerService — Foreground Service
// ─────────────────────────────────────────────────────────────────────────────

class LlmServerService : Service(), ComponentCallbacks2 {

    companion object {
        private const val TAG = "LlmServerService"

        // Acciones
        const val ACTION_START       = "hn.fredi.inferencelocal.ACTION_START"
        const val ACTION_STOP        = "hn.fredi.inferencelocal.ACTION_STOP"
        const val ACTION_QUERY_STATE = "hn.fredi.inferencelocal.ACTION_QUERY_STATE"

        // Extras de inicio
        const val EXTRA_MODEL_NAME = "extra_model_name"
        const val EXTRA_PORT       = "extra_port"

        // Broadcast hacia la UI
        const val BROADCAST_STATE   = "hn.fredi.inferencelocal.STATE"
        const val EXTRA_STATE_CODE  = "state_code"
        const val EXTRA_STATE_MSG   = "state_msg"
        const val EXTRA_STATE_PORT  = "state_port"
        const val EXTRA_STATE_MODEL = "state_model"
        const val EXTRA_STATE_ERROR = "state_error"
        const val EXTRA_STATE_SESSIONS = "state_sessions"
        const val EXTRA_STATE_TOKENS   = "state_tokens"
        const val EXTRA_STATE_MAX_TOKENS = "state_max_tokens"
        const val EXTRA_STATE_BACKEND = "state_backend"
        const val EXTRA_STATE_TPS = "state_tps"
        const val EXTRA_STATE_TTFT = "state_ttft"
        const val EXTRA_STATE_INIT_TIME = "state_init_time"
        const val EXTRA_STATE_RAM = "state_ram"
        const val EXTRA_STATE_MIN_RAM = "state_min_ram"

        const val EXTRA_DEFAULT_NUM_CTX = "default_num_ctx"
        const val EXTRA_DEFAULT_TEMP    = "default_temp"
        const val EXTRA_DEFAULT_TOP_K   = "default_top_k"
        const val EXTRA_DEFAULT_TOP_P   = "default_top_p"

        const val EXTRA_PREFERRED_BACKEND = "extra_preferred_backend"
        const val EXTRA_MAX_SESSIONS      = "extra_max_sessions"
        const val EXTRA_MIN_RAM_MB        = "extra_min_ram_mb"

        // Notificación
        private const val CHANNEL_ID      = "llm_server_channel"
        private const val NOTIFICATION_ID = 1001

        // Defaults
        const val DEFAULT_PORT           = 11434
        const val DEFAULT_MODEL_FILENAME = "gemma-2b-it-gpu-int4.bin"
        const val DEFAULT_MODEL_NAME     = "gemma-2b-it"

        /** Intervalo de refresco de métricas en la notificación (ms) */
        private const val METRICS_REFRESH_MS = 5_000L

        fun startIntent(context: Context,
                        modelName: String = DEFAULT_MODEL_FILENAME,
                        port: Int = DEFAULT_PORT,
                        numCtx: Int = 2048,
                        temp: Float = 0.7f,
                        topK: Int = 40,
                        topP: Float = 0.9f,
                        preferredBackend: String = "GPU",
                        maxSessions: Int = 3,
                        minRamMb: Int = 1536) =
            Intent(context, LlmServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_DEFAULT_NUM_CTX, numCtx)
                putExtra(EXTRA_DEFAULT_TEMP, temp)
                putExtra(EXTRA_DEFAULT_TOP_K, topK)
                putExtra(EXTRA_DEFAULT_TOP_P, topP)
                putExtra(EXTRA_PREFERRED_BACKEND, preferredBackend)
                putExtra(EXTRA_MAX_SESSIONS, maxSessions)
                putExtra(EXTRA_MIN_RAM_MB, minRamMb)
            }

        fun stopIntent(context: Context) =
            Intent(context, LlmServerService::class.java).apply {
                action = ACTION_STOP
            }
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var initJob    : Job? = null
    private var metricsJob : Job? = null   // refresca notificación periódicamente
    private val isRunning  = AtomicBoolean(false)

    private var engine   : LlmEngine?             = null
    private var registry : ModelRegistry?          = null
    private var server   : NettyApplicationEngine? = null

    private var currentModelName = DEFAULT_MODEL_FILENAME
    private var currentPort      = DEFAULT_PORT
    private var currentPhase     = "IDLE"
    private var lastErrorMessage : String? = null

    private var defaultNumCtx: Int = 2048
    private var defaultTemp: Float = 0.7f
    private var defaultTopK: Int = 40
    private var defaultTopP: Float = 0.9f
    
    private var preferredBackend: String = "GPU"
    private var maxSessions: Int = 3
    private var minRamMb: Int = 1536

    private var wakeLock : PowerManager.WakeLock? = null
    private var wifiLock : WifiManager.WifiLock?  = null
    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUERY_STATE -> {
                broadcastCurrentState()
            }

            ACTION_START -> {
                if (isRunning.get()) {
                    Log.w(TAG, "Ya en ejecución — ignorando ACTION_START duplicado")
                    return START_STICKY
                }

                currentModelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: DEFAULT_MODEL_FILENAME
                currentPort      = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                
                defaultNumCtx = intent.getIntExtra(EXTRA_DEFAULT_NUM_CTX, 2048)
                defaultTemp    = intent.getFloatExtra(EXTRA_DEFAULT_TEMP, 0.7f)
                defaultTopK   = intent.getIntExtra(EXTRA_DEFAULT_TOP_K, 40)
                defaultTopP    = intent.getFloatExtra(EXTRA_DEFAULT_TOP_P, 0.9f)
                
                preferredBackend = intent.getStringExtra(EXTRA_PREFERRED_BACKEND) ?: "GPU"
                maxSessions      = intent.getIntExtra(EXTRA_MAX_SESSIONS, 3)
                minRamMb         = intent.getIntExtra(EXTRA_MIN_RAM_MB, 1536)

                startForeground(NOTIFICATION_ID, buildNotification("Iniciando…"))
                acquireLocks()

                initJob = serviceScope.launch { initModelAndServer() }
            }

            ACTION_STOP -> {
                serviceScope.launch { shutdownGracefully() }
            }

            else -> Log.w(TAG, "Intent sin acción reconocida: ${intent?.action}")
        }

        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Presión de memoria CRÍTICA detectada ($level). Liberando recursos...")
                serviceScope.launch {
                    engine?.unload() // Liberación total si el sistema está al límite
                    broadcastCurrentState(message = "Memoria crítica: Modelo descargado para evitar cierre")
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.i(TAG, "Presión de memoria detectada ($level). Limpiando sesiones inactivas...")
                engine?.clearSession() // Limpiar solo sesiones
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        serviceScope.cancel()
        serviceJob.cancel()
        releaseLocks()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inicialización
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun initModelAndServer() {
        try {
            // 1. Validar archivo de modelo
            val modelsDir = getExternalFilesDir(null)
                ?: throw IllegalStateException("No se puede acceder al almacenamiento externo")

            val modelFile = File(modelsDir, currentModelName)
            if (!modelFile.exists()) {
                throw IllegalStateException(
                    "Modelo no encontrado: ${modelFile.absolutePath}\n" +
                            "Usa ADB: adb push $currentModelName /sdcard/Android/data/<package>/files/"
                )
            }

            // 2. Cargar modelo
            broadcastPhase(ServerState.LoadingModel, "Cargando $currentModelName…")
            updateNotification("Cargando modelo…")

            engine = LlmEngine(
                context = applicationContext
            ).apply {
                defaultTemperature = defaultTemp
                defaultTopK = defaultTopK
                defaultTopP = defaultTopP
                defaultNumCtx = defaultNumCtx
                this.preferredBackend = this@LlmServerService.preferredBackend
                this.maxSessions = this@LlmServerService.maxSessions
                this.minAvailableRamMb = this@LlmServerService.minRamMb.toLong()
            }
            // Ya no cargamos aquí, se cargará por demanda en el router
            Log.i(TAG, "Motor LlmEngine inicializado (esperando carga bajo demanda)")

            // 3. Inicializar registro
            registry = ModelRegistry(
                modelsDir = modelsDir
            ).also { reg ->
                reg.initialize()
                reg.setActiveModel(ModelRegistry.Companion.DEFAULT_PRIMARY_NAME)
            }

            // 4. Levantar servidor Ktor
            broadcastPhase(ServerState.StartingServer, "Iniciando servidor en :$currentPort…")
            updateNotification("Iniciando servidor…")

            startKtorServer()
            isRunning.set(true)

            // 5. Emitir estado Running + arrancar loop de métricas
            val runningMsg = "Servidor activo · puerto $currentPort"
            updateNotification(runningMsg)
            broadcastPhase(ServerState.Running(currentPort, DEFAULT_MODEL_NAME), runningMsg)

            startMetricsLoop()

        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar: ${e.message}", e)
            val msg = e.message ?: "Error desconocido"
            lastErrorMessage = msg
            updateNotification("Error: $msg")
            broadcastPhase(ServerState.Error(msg), msg)
            shutdownGracefully()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Loop de métricas — refresca notificación cada N segundos
    // ─────────────────────────────────────────────────────────────────────────

    private fun startMetricsLoop() {
        metricsJob?.cancel()
        metricsJob = serviceScope.launch {
            while (isActive && isRunning.get()) {
                val sessions = engine?.activeSessions ?: 0
                val tokens   = engine?.totalTokensGenerated ?: 0L
                val maxTokens = engine?.maxTokens ?: 2048
                val backend = engine?.getActiveBackend() ?: "Unknown"
                val tps = engine?.lastTokensPerSecond ?: 0.0
                val ttft = engine?.lastTimeToFirstTokenMs ?: 0L
                val initTime = engine?.lastInitTimeMs ?: 0L
                val ram = getMemoryUsage()

                // Actualizar notificación con métricas actuales
                updateNotification(buildMetricsText(sessions, tokens, backend, tps))

                // También emitir broadcast para que la UI se actualice
                broadcastCurrentState(sessions, tokens, maxTokens, backend, tps, ttft, initTime, ram)

                delay(METRICS_REFRESH_MS)
            }
        }
    }

    private fun getMemoryUsage(): String {
        val mi = ActivityManager.MemoryInfo()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(mi)
        
        val avail = mi.availMem / 1024 / 1024
        
        val runtime = Runtime.getRuntime()
        val usedJvm = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        
        // Retornamos JVM usada y RAM de sistema disponible
        return "${usedJvm}M | Libre: ${avail}M"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Servidor Ktor
    // ─────────────────────────────────────────────────────────────────────────

    private fun startKtorServer() {
        val safeEngine   = requireNotNull(engine)   { "LlmEngine es null al iniciar Ktor" }
        val safeRegistry = requireNotNull(registry) { "ModelRegistry es null al iniciar Ktor" }

        server = embeddedServer(
            factory  = Netty,
            port     = currentPort,
            host     = "0.0.0.0",
            configure = {
                connectionGroupSize = 2
                workerGroupSize     = 4
                callGroupSize       = 8
            }
        ) {
            attributes.put(LlmEngineKey,     safeEngine)
            attributes.put(ModelRegistryKey, safeRegistry)
            ollamaApiModule()
        }

        server!!.start(wait = false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Apagado graceful
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun shutdownGracefully() {
        broadcastPhase(ServerState.Stopping, "Deteniendo servidor…")
        updateNotification("Deteniendo…")

        metricsJob?.cancel()
        metricsJob = null

        initJob?.cancel()
        initJob = null

        runCatching {
            server?.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        }.onFailure { Log.w(TAG, "Error al detener Ktor: ${it.message}") }
        server = null

        runCatching {
            engine?.unload()   // también resetea totalTokensGenerated
        }.onFailure { Log.w(TAG, "Error al descargar modelo: ${it.message}") }
        engine = null

        registry?.clearActiveModel()
        registry = null

        isRunning.set(false)

        broadcastPhase(ServerState.Stopped, "Servidor detenido")
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Servicio detenido limpiamente")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast hacia la UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun broadcastPhase(state: ServerState, message: String) {
        currentPhase = when (state) {
            is ServerState.Idle           -> "IDLE"
            is ServerState.LoadingModel   -> "LOADING_MODEL"
            is ServerState.StartingServer -> "STARTING_SERVER"
            is ServerState.Running        -> "RUNNING"
            is ServerState.Error          -> { lastErrorMessage = state.message; "ERROR" }
            is ServerState.Stopping       -> "STOPPING"
            is ServerState.Stopped        -> "STOPPED"
        }
        broadcastCurrentState(message = message)
    }

    /**
     * Emite el estado actual incluyendo métricas del engine.
     * Puede llamarse tanto desde broadcastPhase como desde el loop de métricas.
     */
    private fun broadcastCurrentState(
        sessions: Int    = engine?.activeSessions ?: 0,
        tokens  : Long   = engine?.totalTokensGenerated ?: 0L,
        maxTokens: Int   = engine?.maxTokens ?: 2048,
        backend: String  = engine?.getActiveBackend() ?: "Unknown",
        tps: Double      = engine?.lastTokensPerSecond ?: 0.0,
        ttft: Long       = engine?.lastTimeToFirstTokenMs ?: 0L,
        initTime: Long   = engine?.lastInitTimeMs ?: 0L,
        ram: String      = "—",
        message : String = getDisplayMessage(sessions, tokens, backend, tps)
    ) {
        val intent = Intent(BROADCAST_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE_CODE,     currentPhase)
            putExtra(EXTRA_STATE_MSG,      message)
            putExtra(EXTRA_STATE_PORT,     currentPort)
            putExtra(EXTRA_STATE_MODEL,    currentModelName)
            putExtra(EXTRA_STATE_SESSIONS, sessions)
            putExtra(EXTRA_STATE_TOKENS,   tokens)
            putExtra(EXTRA_STATE_MAX_TOKENS, maxTokens)
            putExtra(EXTRA_STATE_BACKEND,  backend)
            putExtra(EXTRA_STATE_TPS,      tps)
            putExtra(EXTRA_STATE_TTFT,     ttft)
            putExtra(EXTRA_STATE_INIT_TIME, initTime)
            putExtra(EXTRA_STATE_RAM,      ram)

            // Incluir parámetros de configuración actuales
            putExtra(EXTRA_DEFAULT_TEMP, defaultTemp)
            putExtra(EXTRA_DEFAULT_TOP_K, defaultTopK)
            putExtra(EXTRA_DEFAULT_TOP_P, defaultTopP)
            putExtra(EXTRA_DEFAULT_NUM_CTX, defaultNumCtx)
            putExtra(EXTRA_PREFERRED_BACKEND, preferredBackend)
            putExtra(EXTRA_MAX_SESSIONS, maxSessions)
            putExtra(EXTRA_MIN_RAM_MB, minRamMb)

            if (lastErrorMessage != null) putExtra(EXTRA_STATE_ERROR, lastErrorMessage)
        }
        sendBroadcast(intent)
    }

    private fun getDisplayMessage(sessions: Int = 0, tokens: Long = 0L, backend: String = "Unknown", tps: Double = 0.0): String = when (currentPhase) {
        "RUNNING"        -> buildMetricsText(sessions, tokens, backend, tps)
        "LOADING_MODEL"  -> "Cargando modelo…"
        "STARTING_SERVER"-> "Iniciando servidor…"
        "STOPPING"       -> "Deteniendo servidor…"
        "ERROR"          -> lastErrorMessage ?: "Error desconocido"
        else             -> "Servidor detenido"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notificación
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildMetricsText(sessions: Int, tokens: Long, backend: String = "Unknown", tps: Double = 0.0): String {
        val sessionsTxt = when (sessions) {
            0    -> "Idle"
            1    -> "1 Worker"
            else -> "$sessions Workers"
        }
        val tokensTxt = when {
            tokens < 1_000L       -> "$tokens t"
            tokens < 1_000_000L   -> "${"%.1f".format(tokens / 1_000.0)}k t"
            else                  -> "${"%.1f".format(tokens / 1_000_000.0)}M t"
        }
        val speedTxt = if (tps > 0) " · ${"%.1f".format(tps)} t/s" else ""
        return "[$backend] $sessionsTxt · $tokensTxt$speedTxt"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LLM Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Servidor LLM local ejecutándose en segundo plano"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Segunda línea de la notificación: modelo activo
        val subText = "Modelo: ${DEFAULT_MODEL_NAME}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local LLM Server")
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPi)
            .addAction(R.drawable.ic_launcher_foreground, "Detener", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Mostrar el texto completo sin truncar en dispositivos que lo soporten
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WakeLock / WifiLock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:WakeLock"
        ).apply { acquire(6 * 60 * 60 * 1_000L) }  // max 6h

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "$TAG:WifiLock"
        ).apply { acquire() }

        Log.i(TAG, "WakeLock y WifiLock adquiridos")
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        Log.i(TAG, "WakeLock y WifiLock liberados")
    }
}