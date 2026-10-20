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
        val uri = runCatching { URI(clean) }.getOrElse { throw IllegalArgumentException("DirecciÃ³n del Tool Store invÃ¡lida") }
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

@Serializable data class ToolStoreNode(
    val id: String, val label: String, val url: String, val enabled: Boolean = true,
    val useImportedToken: Boolean = true, val hasToken: Boolean = false
)
@Serializable data class ToolStoreSettings(
    val url: String = "", val rewriteSearch: Boolean = true, val primaryEnabled: Boolean = true,
    val stores: List<ToolStoreNode> = emptyList(), val tokens: Map<String, String> = emptyMap()
) {
    fun normalized(): ToolStoreSettings {
        require(stores.size <= 7) { "Máximo ocho catálogos" }
        require(stores.map { it.id }.distinct().size == stores.size) { "Identificadores repetidos" }
        stores.forEach { require(it.id.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && it.id != "principal" && it.label.isNotBlank() && it.label.length <= 40) { "Nombre o identificador inválido" } }
        require(tokens.keys.all { id -> stores.any { it.id == id } } && tokens.values.all { it.length <= 4096 && it.none { c -> c == '\r' || c == '\n' } }) { "Token inválido" }
        val result = copy(url = if (url.isBlank() && !primaryEnabled) "" else ToolStoreEndpoint.normalize(url),
            stores = stores.map { it.copy(url = ToolStoreEndpoint.normalize(it.url)) })
        val active = (if(result.primaryEnabled) listOf(result.url) else emptyList()) + result.stores.filter { it.enabled }.map { it.url }
        require(active.isNotEmpty()) { "Activa al menos un catálogo" }
        val canonical = active.map { java.net.URI(it).let { u -> "${u.scheme.lowercase()}://${u.host.lowercase()}:${if(u.port > 0) u.port else if(u.scheme == "https") 443 else 80}" } }
        require(canonical.distinct().size == canonical.size) { "Direcciones activas repetidas" }
        return result
    }
}
@Serializable private data class StoredToolStores(val settings: ToolStoreSettings, val credentials: Map<String,String> = emptyMap())

/** Public settings never contain stored tokens. Credentials are encrypted with AndroidKeyStore. */
class ToolStoreSettingsStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "toolstore-config.enc"))
    private val legacy = AtomicFile(File(context.noBackupFilesDir, "toolstore-settings.json"))
    companion object {
        private val lock = Any()
        private val mutableChanges = MutableStateFlow<ToolStoreSettings?>(null)
        val changes = mutableChanges.asStateFlow()
    }
    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("toolstore-config", null) as? javax.crypto.SecretKey)?.let { return it }
        return javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder("toolstore-config", 3)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    private fun read(): StoredToolStores {
        if (!file.baseFile.exists()) return StoredToolStores(if(legacy.baseFile.exists()) Json.decodeFromString<ToolStoreSettings>(legacy.readFully().toString(Charsets.UTF_8))
            else ToolStoreSettings(url = runCatching { ConfigVault(context).load().tool_store_url }.getOrDefault("")))
        val bytes = file.readFully(); require(bytes.size >= 28) { "Configuración de catálogos dañada" }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0,12))) }
        return Json.decodeFromString(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
    }
    private fun public(value: StoredToolStores) = value.settings.copy(tokens = emptyMap(), stores = value.settings.stores.map { it.copy(hasToken = !value.credentials[it.id].isNullOrBlank()) })
    fun load(): ToolStoreSettings = synchronized(lock) { public(read()) }
    fun connections(config: AgentConfig, draft: ToolStoreSettings = load()): List<ToolStoreConnection> = synchronized(lock) {
        val settings = draft.normalized()
        val credentials = read().credentials + settings.tokens
        buildList {
            if(settings.primaryEnabled) add(ToolStoreConnection("principal", "Principal", settings.url, config.token))
            settings.stores.filter { it.enabled }.forEach {
                val token = if(it.useImportedToken) config.token else credentials[it.id].orEmpty()
                require(it.useImportedToken || token.isNotBlank()) { "Falta token para ${it.label}" }
                add(ToolStoreConnection(it.id, it.label, it.url, token))
            }
        }
    }
    fun save(settings: ToolStoreSettings) = synchronized(lock) {
        check(!LiteRTEngineService.state.value.busy && !EngineMonitor.state.value.busy && !RemoteJobs.pending) { "Espera a que termine la operación antes de cambiar los catálogos" }
        val normalized = settings.normalized()
        val credentials = (read().credentials + normalized.tokens).filterKeys { id -> normalized.stores.any { it.id == id } }
        require(normalized.stores.all { !it.enabled || it.useImportedToken || !credentials[it.id].isNullOrBlank() }) { "Falta token de un catálogo" }
        val value = StoredToolStores(normalized.copy(tokens = emptyMap()), credentials)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(Json.encodeToString(value).toByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output); mutableChanges.value = public(value); CatalogMonitor.update(emptyList()) }
        catch(e: Exception) { file.failWrite(output); throw e }
    }
}
