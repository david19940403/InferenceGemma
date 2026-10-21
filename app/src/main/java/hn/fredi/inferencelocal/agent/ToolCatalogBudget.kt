package hn.fredi.inferencelocal.agent

import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Preflight estimate, not a tokenizer. Native prefill metrics remain authoritative. */
object ToolCatalogBudget {
    const val SEARCH = "search_tools"
    val reservedNames = setOf(SEARCH, "finish_transaction")
    val searchTool = RemoteTool(SEARCH,
        "Busca otras herramientas por capacidad o nombre. Solo consulta el catálogo; no ejecuta comandos. Úsala si falta la herramienta adecuada.",
        Transport("internal", "", ""), Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string","description":"Capacidad o acción que necesitas; términos breves"}},"required":["query"]}""").jsonObject)
    val finishDefinition = Json.parseToJsonElement("""{"name":"finish_transaction","description":"Finaliza con una respuesta al usuario","parameters":{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}}""").jsonObject

    fun definition(tool: RemoteTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", if (tool.transport.target_host.isBlank()) tool.description else "[${tool.transport.target_host}] ${tool.description}")
        put("parameters", tool.parameters)
    }
    fun estimate(text: String): Int {
        // Punctuation, identifiers and UTF-8 make chars/4 too optimistic for tool schemas.
        val lexical = Regex("[\\p{L}\\p{N}_]+|[^\\s]").findAll(text).sumOf {
            if (it.value.first().isLetterOrDigit() || it.value.first() == '_') ceil(it.value.toByteArray().size / 4.0).toInt() else 1
        }
        return maxOf(ceil(text.toByteArray().size / 2.5).toInt(), lexical)
    }
    fun estimatedTokens(tools: List<RemoteTool>): Int = estimate(JsonArray(tools.map(::definition) + finishDefinition).toString())

    data class Selection(val tools: List<RemoteTool>, val estimatedTokens: Int, val omitted: Int)
    fun select(candidates: List<RemoteTool>, budget: Int, allowSearch: Boolean): Selection {
        val selected = mutableListOf<RemoteTool>()
        if (allowSearch) selected += searchTool
        require(estimatedTokens(selected) <= budget) { "Presupuesto insuficiente para las herramientas internas" }
        for (candidate in candidates.distinctBy { it.name }) {
            require(candidate.name !in reservedNames && candidate.transport.type == "ssh") { "Herramienta reservada o transporte inválido" }
            val compact = candidate.copy(description = candidate.description.take(240))
            if (estimatedTokens(selected + compact) <= budget) selected += compact
        }
        return Selection(selected, estimatedTokens(selected), candidates.distinctBy { it.name }.size - selected.count { it.transport.type == "ssh" })
    }
}
