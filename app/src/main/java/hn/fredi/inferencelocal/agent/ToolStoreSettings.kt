package hn.fredi.inferencelocal.agent

import android.content.Context
import android.util.AtomicFile
import hn.fredi.inferencelocal.remote.RemoteJobs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URI

object ToolStoreEndpoint {
    fun normalize(value: String): String {
        val clean = value.trim().trimEnd('/')
        val uri = runCatching { URI(clean) }.getOrElse { throw IllegalArgumentException("Dirección del Tool Store inválida") }
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535)) {
            "Usa http(s)://IP-o-host:puerto, sin /24, rutas ni credenciales"
        }
        return clean
    }
    fun preset(current: String, host: String): String {
        val uri = runCatching { URI(normalize(current)) }.getOrNull()
        val port = uri?.port?.takeIf { it > 0 } ?: if(uri == null) 8000 else if(uri.scheme == "https") 443 else 80
        return "${uri?.scheme ?: "http"}://$host:$port"
    }
}

@Serializable data class ToolStoreSettings(val url: String = "", val rewriteSearch: Boolean = true)

/** Network preferences contain no token. SSH identities and the catalog token stay in ConfigVault. */
class ToolStoreSettingsStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "toolstore-settings.json"))
    companion object {
        private val lock = Any()
        private val mutableChanges = MutableStateFlow<ToolStoreSettings?>(null)
        val changes = mutableChanges.asStateFlow()
    }
    fun load(): ToolStoreSettings = synchronized(lock) {
        if (file.baseFile.exists()) Json.decodeFromString<ToolStoreSettings>(file.readFully().toString(Charsets.UTF_8))
        else ToolStoreSettings(url = runCatching { ConfigVault(context).load().tool_store_url }.getOrDefault(""))
    }
    fun save(settings: ToolStoreSettings) = synchronized(lock) {
        check(!LiteRTEngineService.state.value.busy && !EngineMonitor.state.value.busy && !RemoteJobs.pending) { "Espera a que termine la operación antes de cambiar el Tool Store" }
        val normalized = settings.copy(url = ToolStoreEndpoint.normalize(settings.url))
        val output = file.startWrite()
        try { output.write(Json.encodeToString(normalized).toByteArray()); file.finishWrite(output); mutableChanges.value = normalized }
        catch(e: Exception) { file.failWrite(output); throw e }
    }
}
