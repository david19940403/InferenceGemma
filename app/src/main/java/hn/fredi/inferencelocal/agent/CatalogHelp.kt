package hn.fredi.inferencelocal.agent

import kotlinx.serialization.json.*
import java.text.Normalizer

/** Catalogue discovery is an exact lookup; it does not require a model to invent an inventory. */
object CatalogHelp {
    fun arguments(query: String): JsonObject? {
        val text = Normalizer.normalize(query.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val discovery = Regex("^(?:que|cuales)\\b.*\\b(?:herramientas|tools|capacidades)\\b|^(?:lista|listar|muestra|mostrar)\\b.*\\b(?:herramientas|tools)\\b|^(?:ayuda|help)\\b|^que puedes hacer\\??$").containsMatchIn(text)
        if (!discovery) return null
        val exact = Regex("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b").find(text)?.value
        val topic = Regex("\\b(?:archivos|carpetas|red|procesos|servicios|discos|eventos|aplicaciones)\\b").find(text)?.value
        val page = Regex("\\bpagina\\s+(\\d+)\\b").find(text)?.groupValues?.get(1) ?: "1"
        val action = when {
            exact != null -> "detail"
            topic != null -> "search"
            Regex("^(lista|listar|muestra|mostrar)\\b").containsMatchIn(text) -> "list"
            else -> "summary"
        }
        return buildJsonObject {
            put("action", action); put("query", exact ?: topic ?: ""); put("page", page); put("limit", "5")
        }
    }

    fun answer(data: JsonObject): String {
        fun value(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            "groups" in data -> "Hay ${value("total")} herramientas disponibles:\n" +
                data.getValue("groups").jsonObject.entries.joinToString("\n") { (name, count) -> "- $name: ${count.jsonPrimitive.content}" } +
                "\nPuedes pedir: lista tools, ayuda de archivos o ayuda de files_read_text."
            "names" in data -> {
                val names = data.getValue("names").jsonArray.map { it.jsonPrimitive.content }
                val next = value("next")
                "Herramientas encontradas: ${value("total")}. Pagina ${value("page")}:\n" +
                    (if (names.isEmpty()) "Sin resultados en esta pagina." else names.joinToString("\n") { "- $it" }) +
                    (if (next.isNotBlank()) "\nHay mas resultados. Pide lista tools pagina $next, indicando el mismo tema si filtraste." else "")
            }
            "name" in data -> {
                val params = data["params"]?.jsonArray.orEmpty().joinToString(", ") { it.jsonPrimitive.content }
                val required = data["required"]?.jsonArray.orEmpty().joinToString(", ") { it.jsonPrimitive.content }
                val choices = data["choices"]?.jsonObject.orEmpty().entries.joinToString("\n") { (name, options) -> "$name: ${options.jsonArray.joinToString(", ") { it.jsonPrimitive.content }}" }
                "${value("name")}\nParametros: ${params.ifBlank { "ninguno" }}\nObligatorios: ${required.ifBlank { "ninguno" }}" +
                    (if (choices.isBlank()) "" else "\n$choices") + "\nEjemplo: ${value("example")}"
            }
            else -> error("Respuesta de ayuda invalida")
        }
    }
}
