package hn.fredi.inferencelocal.api.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WebSearchTool : Tool {
    override val name = "web_search"
    override val loadingMessage = "*(Buscando información en la web...)*\n\n"
    override val description = "Busca información en tiempo real en internet para responder preguntas sobre actualidad o datos que no conoces."
    override val parameters = "{ \"query\": \"términos de búsqueda\" }"

    override suspend fun call(args: Map<String, Any>, context: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val query = args["query"]?.toString() ?: return@withContext "Error: No se proporcionó consulta."
        val maxResults = (context["maxResults"] as? Int) ?: 3

        delay((300L..700L).random())

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://search.brave.com/search?q=$encodedQuery&source=web")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgents.random())
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "es-419,es;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Accept-Encoding", "gzip, deflate, br")
                setRequestProperty("Connection", "keep-alive")
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                setRequestProperty("Sec-Fetch-Dest", "document")
                setRequestProperty("Sec-Fetch-Mode", "navigate")
                setRequestProperty("Sec-Fetch-Site", "none")
                setRequestProperty("Sec-Fetch-User", "?1")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                connectTimeout = 10_000
                readTimeout = 12_000
                instanceFollowRedirects = true
            }

            val stream = when (connection.contentEncoding?.lowercase()) {
                "gzip"    -> java.util.zip.GZIPInputStream(connection.inputStream)
                "deflate" -> java.util.zip.InflaterInputStream(connection.inputStream)
                "br"      -> connection.inputStream // Brotli requiere librería externa, fallback
                else      -> connection.inputStream
            }

            val html = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // Detectar bloqueo / captcha
            if (html.length < 1000 || html.contains("captcha", ignoreCase = true)
                || html.contains("blocked", ignoreCase = true)
                || html.contains("Access denied", ignoreCase = true)) {
                return@withContext "Error: Búsqueda bloqueada por Brave. Intenta más tarde."
            }

            fun String.cleanHtml(): String = replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            // ── Extracción de bloques de resultado ──────────────────────────────
            // Brave envuelve cada resultado en <div class="snippet ...">...</div>
            val blockRegex = Regex(
                """<div[^>]+class="[^"]*snippet[^"]*"[^>]*>(.*?)</div>\s*</div>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )

            // Título: primer <a> con class que contiene "title" dentro del bloque
            val titleRegex = Regex(
                """<a[^>]+class="[^"]*title[^"]*"[^>]*>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )

            // Descripción: <p> o <div> con class que contiene "desc" o "snippet-description"
            val descRegex = Regex(
                """<(?:p|div|span)[^>]+class="[^"]*(?:desc|snippet-description|snippet__content)[^"]*"[^>]*>(.*?)</(?:p|div|span)>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )

            // URL: atributo href del enlace de título
            val hrefRegex = Regex(
                """<a[^>]+class="[^"]*title[^"]*"[^>]+href="([^"]+)"""",
                RegexOption.IGNORE_CASE
            )

            val results = mutableListOf<String>()

            for (block in blockRegex.findAll(html).take(maxResults * 2)) {
                val blockHtml = block.groupValues[1]

                val title = titleRegex.find(blockHtml)
                    ?.groupValues?.get(1)
                    ?.cleanHtml()
                    ?.takeIf { it.isNotBlank() } ?: continue

                val desc = descRegex.find(blockHtml)
                    ?.groupValues?.get(1)
                    ?.cleanHtml()
                    ?.takeIf { it.isNotBlank() }
                    ?: ""

                val href = hrefRegex.find(blockHtml)
                    ?.groupValues?.get(1)
                    ?.takeIf { it.startsWith("http") }
                    ?: ""

                results += buildString {
                    append("- $title")
                    if (href.isNotBlank()) append("\n  URL: $href")
                    if (desc.isNotBlank()) append("\n  $desc")
                }

                if (results.size >= maxResults) break
            }

            // ── Fallback: si los selectores de bloque no matchean (Brave cambia su HTML) ──
            if (results.isEmpty()) {
                val fbTitles = Regex(
                    """<span[^>]+class="[^"]*title[^"]*"[^>]*>(.*?)</span>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).findAll(html).map { it.groupValues[1].cleanHtml() }.filter { it.isNotBlank() }.toList()

                val fbDescs = Regex(
                    """<p[^>]+class="[^"]*description[^"]*"[^>]*>(.*?)</p>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).findAll(html).map { it.groupValues[1].cleanHtml() }.filter { it.isNotBlank() }.toList()

                fbTitles.take(maxResults).forEachIndexed { i, title ->
                    val desc = fbDescs.getOrElse(i) { "" }
                    results += "- $title${if (desc.isNotBlank()) "\n  $desc" else ""}"
                }
            }

            if (results.isEmpty()) "No se encontraron resultados para '$query'."
            else "Resultados para '$query':\n\n${results.joinToString("\n\n")}"

        } catch (e: java.net.SocketTimeoutException) {
            "Error: Timeout al conectar con Brave Search."
        } catch (e: java.net.UnknownHostException) {
            "Error: Sin conexión a internet."
        } catch (e: Exception) {
            "Error inesperado: ${e.message}"
        }
    }

    // Fuera de la función — lista de User-Agents
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    )

    override fun shouldActivate(content: String): Boolean = content.startsWith("search:", ignoreCase = true)
}
