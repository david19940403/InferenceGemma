package hn.fredi.inferencelocal.orchestrator.resolver

import hn.fredi.inferencelocal.orchestrator.models.ToolSearchRequest
import hn.fredi.inferencelocal.orchestrator.models.ToolSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class ToolStoreClient(
    baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val endpoint = baseUrl.trimEnd('/') + "/tools/search"

    suspend fun getToolsForQuery(query: String, topK: Int = 3) = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "La instrucción está vacía" }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter().use {
                it.write(json.encodeToString(ToolSearchRequest(query, topK.coerceIn(1, 10))))
            }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(connection.responseCode in 200..299) {
                "Tool Store respondió ${connection.responseCode}: ${body.take(300)}"
            }
            json.decodeFromString<ToolSearchResponse>(body).tools
        } finally {
            connection.disconnect()
        }
    }
}
