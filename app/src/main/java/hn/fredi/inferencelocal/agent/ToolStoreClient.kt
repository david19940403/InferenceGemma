package hn.fredi.inferencelocal.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

class ToolStoreClient(private val config: AgentConfig) {
    @Serializable private data class SearchResponse(val tools: List<RemoteTool>)
    suspend fun probe() { search("__connection_test__") }
    suspend fun search(query: String): List<RemoteTool> = withContext(Dispatchers.IO) {
        val conn = URL(ToolStoreEndpoint.normalize(config.tool_store_url) + "/tools/search").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 2500; conn.readTimeout = 4000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            if (config.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${config.token}")
            conn.doOutput = true
            conn.outputStream.use { it.write(buildJsonObject { put("query", query); put("top_k", 16) }.toString().toByteArray()) }
            check(conn.responseCode == 200) { when(conn.responseCode) {
                401,403 -> "Tool Store rechazó el token importado (HTTP ${conn.responseCode})"
                else -> "Tool Store respondió HTTP ${conn.responseCode}"
            } }
            val bytes = conn.inputStream.use { it.readBytesBounded(64 * 1024) }
            Json { ignoreUnknownKeys = true }.decodeFromString<SearchResponse>(bytes.toString(Charsets.UTF_8)).tools.also {
                require(it.all { t -> t.name.matches(Regex("[a-z][a-z0-9_]{0,63}")) && t.name !in ToolCatalogBudget.reservedNames } && it.size <= 16 && it.map { t -> t.name }.distinct().size == it.size)
                require(it.all { t -> t.transport.type == "ssh" && config.hosts.any { h -> h.host == t.transport.target_host } }) { "Catálogo con hosts no autorizados" }
            }
        } finally { conn.disconnect() }
    }
}

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "Salida excede el límite" }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
