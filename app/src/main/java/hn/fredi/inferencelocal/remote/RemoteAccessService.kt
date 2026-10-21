package hn.fredi.inferencelocal.remote

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import hn.fredi.inferencelocal.MainActivity
import hn.fredi.inferencelocal.R
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface

data class RemoteAccessState(val running: Boolean = false, val starting: Boolean = false, val urls: List<String> = emptyList(), val error: String? = null)
class RemoteAccessService : Service() {
    companion object {
        const val START = "remote.START"
        const val STOP = "remote.STOP"
        val connections = RemoteConnectionRegistry()
        private val mutableState = MutableStateFlow(RemoteAccessState())
        val state = mutableState.asStateFlow()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var destroyed = false
    private var server: ApplicationEngine? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("remote", "Acceso remoto", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2003, NotificationCompat.Builder(this,"remote").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Acceso remoto activo").setContentText("Interfaz web y cliente en la red local").setContentIntent(pending).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if(state.value.running || state.value.starting) return START_NOT_STICKY
        mutableState.value = RemoteAccessState(starting = true)
        connections.purgeAll()
        scope.launch {
            try {
                val settings = RemoteSettingsStore(this@RemoteAccessService).load()
                synchronized(lifecycleLock) {
                    if(destroyed) return@launch
                    server = embeddedServer(Netty, host = "0.0.0.0", port = settings.port) { remoteModule(this@RemoteAccessService, settings) }.also { it.start(false) }
                }
                val urls = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                    .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }.map { "http://${it.hostAddress}:${settings.port}" }.distinct()
                synchronized(lifecycleLock) {
                    if(!destroyed) mutableState.value = RemoteAccessState(running = true, urls = urls.ifEmpty { listOf("http://127.0.0.1:${settings.port}") })
                }
            } catch(e: Exception) {
                mutableState.value = RemoteAccessState(error = e.message ?: "No se pudo abrir el puerto")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        connections.purgeAll()
        scope.cancel()
        val stopping = synchronized(lifecycleLock) { destroyed = true; server.also { server = null } }
        mutableState.value = RemoteAccessState(starting = true, error = state.value.error)
        CoroutineScope(Dispatchers.IO).launch {
            try { stopping?.stop(300,1000) }
            finally { mutableState.value = RemoteAccessState(error = state.value.error) }
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
