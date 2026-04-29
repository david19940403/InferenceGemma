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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json



// ─────────────────────────────────────────────────────────────────────────────
// LlmServerService  –  Foreground Service
// ─────────────────────────────────────────────────────────────────────────────

class LlmServerService : Service() {

    companion object {
        private const val TAG = "LlmServerService"

        // Intents para controlar el servicio desde la UI
        const val ACTION_START = "com.example.localllmserver.ACTION_START"
        const val ACTION_STOP  = "com.example.localllmserver.ACTION_STOP"

        // Notificación
        private const val NOTIFICATION_CHANNEL_ID = "llm_server_channel"
        private const val NOTIFICATION_ID = 1001

        // Puerto del servidor HTTP
        const val SERVER_PORT = 11434

        // Ruta del modelo dentro del almacenamiento del dispositivo.
        // El usuario debe colocar el archivo .bin en:
        //   /sdcard/Android/data/com.example.localllmserver/files/gemma-2b-it-gpu.bin
        // O bien en assets si el APK lo empaqueta (no recomendado por tamaño).
        const val MODEL_FILE_NAME = "gemma-2b-it-gpu-int4.bin"
        const val EXTRA_MODEL_NAME = "extra_model_name"
        // Broadcast que emite el servicio para informar a la UI
        const val BROADCAST_STATUS = "com.example.localllmserver.STATUS"
        const val EXTRA_RUNNING    = "running"
        const val EXTRA_ERROR      = "error"
    }

    // ── Ciclo de vida del servicio ────────────────────────────────────────────
    private var currentModelName = MODEL_FILE_NAME
    private val serviceJob  = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var llmInference: LlmInference? = null
    private var ktorServer: NettyApplicationEngine? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    // ── onStartCommand ───────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentModelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: MODEL_FILE_NAME
                startForeground(NOTIFICATION_ID, buildNotification("Iniciando modelo…"))
                acquireLocks()
                serviceScope.launch { initModelAndServer(currentModelName) }
            }
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
        }
        return START_STICKY   // El sistema reinicia el servicio si muere
    }

    // ── onBind ───────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    // ── onDestroy ────────────────────────────────────────────────────────────

    override fun onDestroy() {
        stopServer()
        serviceJob.cancel()
        releaseLocks()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inicialización del modelo y del servidor
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun initModelAndServer(modelFileName: String) {
        try {
            // 1. Cargar el modelo en GPU vía MediaPipe LLM Inference
            Log.i(TAG, "Cargando modelo: $modelFileName")
            updateNotification("Cargando $modelFileName...")

            llmInference = withContext(Dispatchers.IO) {
                val modelPath = "${getExternalFilesDir(null)?.absolutePath}/$modelFileName"

                // 1. Configuración del modelo (Base)
                // El backend GPU se detecta automáticamente si el modelo es compatible
                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .setMaxTopK(40)
                    .setMaxTokens(4096)
                    .build()

                // Creamos la instancia principal
                val inference = LlmInference.createFromOptions(applicationContext, options)


                inference
            }

            Log.i(TAG, "Modelo cargado correctamente. Iniciando servidor HTTP…")
            updateNotification("Servidor activo en :$SERVER_PORT")

            // 2. Lanzar el servidor Ktor
            startKtorServer()
            broadcastStatus(running = true)

        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar: ${e.message}", e)
            updateNotification("Error: ${e.message}")
            broadcastStatus(running = false, error = e.message)
            stopSelf()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Servidor Ktor
    // ─────────────────────────────────────────────────────────────────────────

    private fun startKtorServer() {
        // 1. Crear el servidor
        ktorServer = embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", configure = {connectionGroupSize = 2
            workerGroupSize = 5
            callGroupSize = 10
        }) {
            // 2. Inyectar el atributo DENTRO del bloque de configuración de la Application
            attributes.put(LlmInferenceKey, llmInference!!)


            // Llamar al módulo
            llmServerModule()
        }

        // 3. Iniciar el servidor fuera del constructor del servidor
        ktorServer?.start(wait = false)

        Log.i(TAG, "Servidor Ktor activo en 0.0.0.0:$SERVER_PORT")
    }

    private fun stopServer() {
        try {
            ktorServer?.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
            ktorServer = null
            llmInference?.close()
            llmInference = null
            Log.i(TAG, "Servidor detenido y modelo liberado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener el servidor: ${e.message}", e)
        }
        broadcastStatus(running = false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WakeLock y WifiLock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        // WakeLock: impide que la CPU entre en suspensión profunda
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:WakeLock"
        ).apply {
            acquire(6 * 60 * 60 * 1_000L) // Máximo 6 horas de seguridad
        }

        // WifiLock: mantiene la conexión Wi-Fi activa en modo alto rendimiento
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "$TAG:WifiLock"
        ).apply { acquire() }

        Log.i(TAG, "WakeLock y WifiLock adquiridos")
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        Log.i(TAG, "WakeLock y WifiLock liberados")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notificación del Foreground Service
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "LLM Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Servidor LLM ejecutándose en segundo plano"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LlmServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Local LLM Server")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)      // Asegúrate de tener este drawable
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Detener", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast hacia la UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun broadcastStatus(running: Boolean, error: String? = null) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_RUNNING, running)
            error?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }
}