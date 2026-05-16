package hn.fredi.inferencelocal.api.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class UrlReaderTool : Tool {
    override val name = "url_reader"
    override val loadingMessage = "*(Leyendo contenido de la URL...)*\n\n"
    override val description = "Lee y extrae el texto principal de una página web a partir de su URL."
    override val parameters = "{ \"url\": \"https://ejemplo.com\" }"

    override suspend fun call(args: Map<String, Any>, context: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val urlString = args["url"]?.toString() 
            ?: args["link"]?.toString() 
            ?: args["address"]?.toString()
            ?: args["href"]?.toString()
            ?: return@withContext "Error: No se proporcionó URL en los argumentos: $args"
        
        android.util.Log.i("UrlReaderTool", "Leyendo contenido de: $urlString")
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

            val rawText = connection.inputStream.bufferedReader().use { it.readText() }
            
            // Limpieza básica de HTML
            val cleanText = rawText.replace(Regex("<script.*?>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<style.*?>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val maxTokens = (context["maxTokens"] as? Int) ?: 2048
            val estimated = (cleanText.length / 3).coerceAtLeast(1)
            
            if (estimated <= maxTokens) return@withContext cleanText

            val ratio = maxTokens.toFloat() / estimated
            val targetChars = (cleanText.length * ratio).toInt()
            
            return@withContext cleanText.take(targetChars) + "... [Contenido truncado]"
        } catch (e: Exception) {
            "Error al leer la URL: ${e.message}"
        }
    }

    override fun shouldActivate(content: String): Boolean = content.startsWith("read:", ignoreCase = true)
}
