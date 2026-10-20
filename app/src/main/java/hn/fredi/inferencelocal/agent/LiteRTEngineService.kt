package hn.fredi.inferencelocal.agent

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import hn.fredi.inferencelocal.MainActivity
import hn.fredi.inferencelocal.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TerminalState(val busy: Boolean = false, val message: String = "Importa la configuración y escribe una instrucción.", val result: String = "")
class LiteRTEngineService : Service() {
    companion object {
        private val mutableState = MutableStateFlow(TerminalState())
        val state = mutableState.asStateFlow()
        const val RUN = "hn.fredi.inferencelocal.agent.RUN"
        const val CANCEL = "hn.fredi.inferencelocal.agent.CANCEL"
        const val LOAD = "hn.fredi.inferencelocal.agent.LOAD"
        const val UNLOAD = "hn.fredi.inferencelocal.agent.UNLOAD"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var task: Job? = null
    private var engine: LiteRTAgentEngine? = null
    private var activeSettings: EngineSettings? = null
    private var pinned = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("infrastructure", "Terminal de infraestructura", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2002, NotificationCompat.Builder(this, "infrastructure").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Engine de la terminal").setContentText("Control local del modelo y tareas SSH").setContentIntent(pending).setOngoing(true).build())
    }
    private fun getEngine(): LiteRTAgentEngine {
        val settings = EngineSettingsStore(this).load()
        if (engine != null && settings == activeSettings) return engine!!
        releaseEngine()
        activeSettings = settings
        return LiteRTAgentEngine(this, settings).also { engine = it }
    }
    private fun releaseEngine() { try { engine?.close() } finally { engine = null; activeSettings = null } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == CANCEL) { task?.cancel(); if (task == null) stopSelf(); return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        if (action !in listOf(RUN, LOAD, UNLOAD)) { if (!pinned) stopSelf(); return START_NOT_STICKY }
        if (action == LOAD) pinned = true
        if (action == UNLOAD) pinned = false
        val query = intent?.getStringExtra("query").orEmpty()
        val startingMessage = when (action) { LOAD -> "Cargando engine…"; UNLOAD -> "Liberando modelo…"; else -> "Preparando transacción…" }
        mutableState.value = mutableState.value.copy(busy = true, message = startingMessage, result = if (action == RUN) "" else mutableState.value.result)
        EngineMonitor.update { it.copy(taskActive = action == RUN, phase = when(action) { LOAD -> EnginePhase.LOADING; UNLOAD -> EnginePhase.RELEASING; else -> it.phase }, message = startingMessage, error = null) }
        task = scope.launch {
            var pool: SSHConnectionPool? = null
            var error: String? = null
            var result = mutableState.value.result
            var message = "Engine listo"
            try {
                when (action) {
                    LOAD -> { getEngine().warmup(); message = "Engine cargado y listo para la terminal" }
                    UNLOAD -> message = "Modelo descargado"
                    else -> {
                        val config = ConfigVault(this@LiteRTEngineService).load()
                        val localEngine = getEngine()
                        val connections = SSHConnectionPool(config).also { pool = it }
                        val worker = AgenticLoopWorker(ToolStoreClient(config)::search, localEngine, ToolExecutor(connections::execute), config.hosts) { progress ->
                            mutableState.value = mutableState.value.copy(message = progress)
                            EngineMonitor.update { it.copy(message = progress) }
                        }
                        result = withTimeout(300000) { worker.processUserIntent(query) }
                        message = "Transacción finalizada"
                    }
                }
            } catch (e: CancellationException) {
                if (action == LOAD) pinned = false
                message = "Operación cancelada o tiempo agotado"
                if (action == RUN) result = "Una operación remota iniciada puede continuar; consulta su estado antes de repetirla."
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                message = "No se pudo completar"
                if (action == RUN) result = error
                if (!EngineMonitor.state.value.modelLoaded) pinned = false
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { pool?.close() }
                    if (!pinned) runCatching { releaseEngine() }
                    EngineMonitor.update { it.copy(taskActive = false, phase = if(error != null) EnginePhase.ERROR else if(it.modelLoaded) EnginePhase.READY else EnginePhase.IDLE, message = message, error = error) }
                    mutableState.value = TerminalState(false, message, result)
                }
                if (!pinned) stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        val activeTask = task
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            activeTask?.join()
            runCatching { releaseEngine() }
            EngineMonitor.update { it.copy(taskActive = false) }
            if (mutableState.value.busy) mutableState.value = mutableState.value.copy(busy = false, message = "Servicio detenido")
        }
        super.onDestroy()
    }
}
