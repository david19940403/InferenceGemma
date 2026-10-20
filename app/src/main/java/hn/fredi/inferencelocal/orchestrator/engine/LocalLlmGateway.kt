package hn.fredi.inferencelocal.orchestrator.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

interface InferenceGateway {
    suspend fun generate(prompt: String): String
}

class LocalLlmGateway(
    private val model: String,
    private val endpoint: String = "http://127.0.0.1:11434/api/generate",
    private val json: Json = Json { ignoreUnknownKeys = true }
) : InferenceGateway {
    @Serializable private data class Request(
        val model: String,
        val prompt: String,
        val system: String,
        val stream: Boolean = false,
        val format: String = "json"
    )
    @Serializable private data class Response(val response: String)

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val request = Request(
                model = model,
                prompt = prompt,
                system = "Eres un orquestador de infraestructura. Responde exclusivamente con el JSON solicitado."
            )
            connection.outputStream.bufferedWriter().use { it.write(json.encodeToString(request)) }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(connection.responseCode in 200..299) {
                "El motor local respondió ${connection.responseCode}: ${body.take(300)}"
            }
            json.decodeFromString<Response>(body).response
        } finally {
            connection.disconnect()
        }
    }
}
