package hn.kwikstop.inferencelocal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import hn.kwikstop.inferencelocal.MainActivity
import hn.kwikstop.inferencelocal.R
import hn.kwikstop.inferencelocal.api.LlmEngineKey
import hn.kwikstop.inferencelocal.api.ModelRegistryKey
import hn.kwikstop.inferencelocal.api.engine.LlmEngine
import hn.kwikstop.inferencelocal.api.ollamaApiModule
import hn.kwikstop.inferencelocal.api.registry.ModelRegistry
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────────────────────────────────────
// Estados del servicio — se emiten via Broadcast para actualizar la UI
// ─────────────────────────────────────────────────────────────────────────────

sealed class ServerState {
    object Idle                              : ServerState()
    object LoadingModel                      : ServerState()
    object StartingServer                    : ServerState()
    data class Running(val port: Int,
                       val modelName: String): ServerState()
    data class Error(val message: String)    : ServerState()
    object Stopping                          : ServerState()
    object Stopped                           : ServerState()
}

// ─────────────────────────────────────────────────────────────────────────────
// LlmServerService — Foreground Service
// ─────────────────────────────────────────────────────────────────────────────

class LlmServerService : Service() {

    // ── Companion ─────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "LlmServerService"

        // Acciones del Intent
        const val ACTION_START = "hn.kwikstop.inferencelocal.ACTION_START"
        const val ACTION_STOP  = "hn.kwikstop.inferencelocal.ACTION_STOP"
        const val ACTION_QUERY_STATE  = "hn.kwikstop.inferencelocal.ACTION_QUERY_STATE"

        // Extras del Intent de inicio
        const val EXTRA_MODEL_NAME = "extra_model_name"
        const val EXTRA_PORT       = "extra_port"

        // Broadcast de estado hacia la UI
        const val BROADCAST_STATE    = "hn.kwikstop.inferencelocal.STATE"
        const val EXTRA_STATE_CODE   = "state_code"     // String del enum
        const val EXTRA_STATE_MSG    = "state_msg"      // Mensaje legible
        const val EXTRA_STATE_PORT   = "state_port"
        const val EXTRA_STATE_MODEL  = "state_model"
        const val EXTRA_STATE_ERROR  = "state_error"

        // Notificación
        private const val CHANNEL_ID       = "llm_server_channel"
        private const val NOTIFICATION_ID  = 1001

        // Defaults
        const val DEFAULT_PORT            = 11434
        const val DEFAULT_MODEL_FILENAME  = "gemma-2b-it-gpu-int4.bin"
        const val DEFAULT_MODEL_NAME      = "gemma-2b-it"

        // Helpers para construir intents desde la UI
        fun startIntent(context: Context, modelName: String = DEFAULT_MODEL_FILENAME, port: Int = DEFAULT_PORT) =
            Intent(context, LlmServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_PORT, port)
            }

        fun stopIntent(context: Context) =
            Intent(context, LlmServerService::class.java).apply {
                action = ACTION_STOP
            }
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    /** Corutina supervisora: si un hijo falla, los demás siguen corriendo */
    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var initJob: Job? = null          // Job de la tarea de inicialización
    private val isRunning = AtomicBoolean(false)

    private var engine   : LlmEngine?              = null
    private var registry : ModelRegistry?           = null
    private var server   : NettyApplicationEngine?  = null

    private var currentModelName = DEFAULT_MODEL_FILENAME
    private var currentPort      = DEFAULT_PORT

    private var wakeLock : PowerManager.WakeLock?   = null
    private var wifiLock : WifiManager.WifiLock?    = null

    // ── Ciclo de vida del Service ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUERY_STATE -> {
                Log.i(TAG, "Consulta de estado recibida. Fase actual: $currentPhase")
                // Respondemos con lo que el servicio SABE que está pasando
                emitState(currentPhase, getDisplayMessage(), lastErrorMessage)
            }
            ACTION_START -> {
                if (isRunning.get()) {
                    Log.w(TAG, "El servicio ya está corriendo — ignorando ACTION_START duplicado")
                    return START_STICKY
                }

                currentModelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: DEFAULT_MODEL_FILENAME
                currentPort      = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)

                // Promover a Foreground inmediatamente para que Android no mate el proceso
                startForeground(NOTIFICATION_ID, buildNotification("Iniciando…"))
                acquireLocks()

                initJob = serviceScope.launch {
                    initModelAndServer()
                }
            }

            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP recibido")
                serviceScope.launch { shutdownGracefully() }
            }


            else -> Log.w(TAG, "Intent sin acción reconocida: ${intent?.action}")
        }

        return START_STICKY
    }
    private var currentPhase: String = "IDLE"
    private var lastErrorMessage: String? = null
    private fun emitState(phase: String, msg: String, err: String? = null) {
        val intent = Intent(BROADCAST_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE_CODE, phase)
            putExtra(EXTRA_STATE_MSG, msg)
            putExtra(EXTRA_STATE_ERROR, err)
            putExtra(EXTRA_STATE_PORT, currentPort)
            putExtra(EXTRA_STATE_MODEL, currentModelName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Estado re-emitido a la UI: $phase")
    }

    private fun getDisplayMessage(): String {
        return when(currentPhase) {
            "RUNNING" -> "Servidor activo en 0.0.0.0:$currentPort"
            "LOADING_MODEL" -> "Cargando modelo…"
            "ERROR" -> lastErrorMessage ?: "Error desconocido"
            "IDLE", "STOPPED" -> "Servidor detenido"
            else -> "Procesando..."
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — limpiando recursos")
        serviceScope.cancel()   // cancela todas las corutinas hijas
        serviceJob.cancel()
        releaseLocks()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inicialización — carga modelo → levanta servidor
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun initModelAndServer() {
        try {
            // ── 1. Validar que el archivo del modelo exista ────────────────
            val modelsDir = getExternalFilesDir(null)
                ?: throw IllegalStateException("No se puede acceder al almacenamiento externo")

            val modelFile = File(modelsDir, currentModelName)
            if (!modelFile.exists()) {
                throw IllegalStateException(
                    "Archivo de modelo no encontrado: ${modelFile.absolutePath}\n" +
                            "Usa ADB para copiarlo:\n" +
                            "  adb push $currentModelName /sdcard/Android/data/<package>/files/"
                )
            }

            // ── 2. Cargar modelo ──────────────────────────────────────────
            broadcastState(ServerState.LoadingModel, "Cargando $currentModelName…")
            updateNotification("Cargando modelo…")

            engine = LlmEngine(
                context   = applicationContext,
                modelPath = modelFile.absolutePath
            )
            engine!!.load()   // suspende hasta que el modelo esté en GPU
            Log.i(TAG, "Modelo cargado: ${modelFile.absolutePath}")

            // ── 3. Inicializar el registro de modelos ─────────────────────
            registry = ModelRegistry(
                modelsDir       = modelsDir,
                modelFileName   = currentModelName   // ← "gemma-2b-it-gpu-int4.bin"
            ).also { reg ->
                reg.initialize()
                reg.setActiveModel(ModelRegistry.DEFAULT_MODEL)
            }

            // ── 4. Levantar el servidor Ktor ──────────────────────────────
            broadcastState(ServerState.StartingServer, "Iniciando servidor en :$currentPort…")
            updateNotification("Iniciando servidor…")

            startKtorServer()
            isRunning.set(true)

            val msg = "Servidor activo en 0.0.0.0:$currentPort"
            Log.i(TAG, msg)
            updateNotification(msg)
            broadcastState(
                ServerState.Running(port = currentPort, modelName = DEFAULT_MODEL_NAME),
                msg
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar: ${e.message}", e)
            val msg = e.message ?: "Error desconocido"
            updateNotification("Error: $msg")
            broadcastState(ServerState.Error(msg), msg)
            // Detener el servicio limpiamente tras el error
            shutdownGracefully()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Servidor Ktor
    // ─────────────────────────────────────────────────────────────────────────

    private fun startKtorServer() {
        val safeEngine   = requireNotNull(engine)   { "LlmEngine es null al iniciar Ktor" }
        val safeRegistry = requireNotNull(registry) { "ModelRegistry es null al iniciar Ktor" }

        server = embeddedServer(
            factory = Netty,
            port    = currentPort,
            host    = "0.0.0.0",
            configure = {
                connectionGroupSize = 2   // hilos de aceptación de conexiones
                workerGroupSize     = 4   // hilos de I/O de Netty
                callGroupSize       = 8   // hilos de procesamiento de requests
            }
        ) {
            // Inyectar dependencias en los atributos de la Application
            // (disponibles para todos los handlers via call.application.attributes)
            attributes.put(LlmEngineKey,     safeEngine)
            attributes.put(ModelRegistryKey, safeRegistry)

            // Instalar rutas y plugins
            ollamaApiModule()
        }

        server!!.start(wait = false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Apagado graceful
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun shutdownGracefully() {
        broadcastState(ServerState.Stopping, "Deteniendo servidor…")
        updateNotification("Deteniendo…")

        // Cancelar la inicialización si aún está en curso
        initJob?.cancel()
        initJob = null

        // Detener Ktor con período de gracia de 1s y timeout de 3s
        runCatching {
            server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
        }.onFailure { Log.w(TAG, "Error al detener Ktor: ${it.message}") }
        server = null

        // Descargar el modelo de GPU
        runCatching {
            engine?.unload()
        }.onFailure { Log.w(TAG, "Error al descargar modelo: ${it.message}") }
        engine = null

        // Limpiar registro
        registry?.clearActiveModel()
        registry = null

        isRunning.set(false)

        broadcastState(ServerState.Stopped, "Servidor detenido")
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Servicio detenido limpiamente")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast hacia la UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun broadcastState(state: ServerState, message: String) {
        // ACTUALIZACIÓN: Guardamos la fase actual para recuperarla luego
        currentPhase = when (state) {
            is ServerState.Idle           -> "IDLE"
            is ServerState.LoadingModel   -> "LOADING_MODEL"
            is ServerState.StartingServer -> "STARTING_SERVER"
            is ServerState.Running        -> "RUNNING"
            is ServerState.Error          -> "ERROR"
            is ServerState.Stopping       -> "STOPPING"
            is ServerState.Stopped        -> "STOPPED"
        }
        lastErrorMessage = if (state is ServerState.Error) state.message else null

        val intent = Intent(BROADCAST_STATE).apply {
            setPackage(packageName) // Asegúrate de que siempre lleve el package
            putExtra(EXTRA_STATE_MSG, message)
            putExtra(EXTRA_STATE_CODE, currentPhase)
            putExtra(EXTRA_STATE_PORT, currentPort)
            putExtra(EXTRA_STATE_MODEL, currentModelName)
            if (lastErrorMessage != null) putExtra(EXTRA_STATE_ERROR, lastErrorMessage)
        }
        sendBroadcast(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WakeLock / WifiLock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        // WakeLock parcial: mantiene la CPU activa sin encender pantalla
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:WakeLock"
        ).apply {
            acquire(6 * 60 * 60 * 1_000L)   // máximo 6 horas de seguridad
        }

        // WifiLock alto rendimiento: evita que el Wi-Fi entre en modo bajo consumo
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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

    // ─────────────────────────────────────────────────────────────────────────
    // Notificación del Foreground Service
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LLM Server",
            NotificationManager.IMPORTANCE_LOW          // silencioso, sin vibración
        ).apply {
            description  = "Servidor LLM ejecutándose en segundo plano"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        // Tap en la notificación → abre MainActivity
        val openAppPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Botón "Detener" en la notificación
        val stopPi = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local LLM Server")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPi)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Detener",
                stopPi
            )
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}