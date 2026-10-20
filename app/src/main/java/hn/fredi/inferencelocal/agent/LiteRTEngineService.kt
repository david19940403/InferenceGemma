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
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var task: Job? = null
    private val concurrency = TaskConcurrencyManager()
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("infrastructure", "Terminal de infraestructura", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2002, NotificationCompat.Builder(this, "infrastructure").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Terminal de infraestructura").setContentText("Transacción local en curso").setContentIntent(pending).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { task?.cancel(); return START_NOT_STICKY }
        if (intent?.action != RUN) { stopSelf(startId); return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        val query = intent.getStringExtra("query").orEmpty()
        mutableState.value = TerminalState(true, "Preparando transacción…")
        task = scope.launch {
            var engine: LiteRTAgentEngine? = null
            var pool: SSHConnectionPool? = null
            try {
                val config = ConfigVault(this@LiteRTEngineService).load()
                val localEngine = LiteRTAgentEngine(this@LiteRTEngineService, config).also { engine = it }
                val connections = SSHConnectionPool(config).also { pool = it }
                val worker = AgenticLoopWorker(ToolStoreClient(config)::search, localEngine, ToolExecutor(connections::execute), config.hosts) {
                    mutableState.value = TerminalState(true, it)
                }
                val answer = concurrency.run { withTimeout(300000) { worker.processUserIntent(query) } }
                mutableState.value = TerminalState(false, "Transacción finalizada", answer)
            } catch (e: CancellationException) {
                mutableState.value = TerminalState(false, "Transacción cancelada o tiempo agotado", "Una operación remota iniciada puede continuar; consulta su estado antes de repetirla.")
                throw e
            } catch (e: Exception) {
                mutableState.value = TerminalState(false, "No se pudo completar", e.message ?: e.javaClass.simpleName)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { pool?.close() }; runCatching { engine?.close() } }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
