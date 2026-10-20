package hn.fredi.inferencelocal.orchestrator.worker

import hn.fredi.inferencelocal.orchestrator.models.RemoteTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object CommandTemplate {
    private val placeholder = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")

    fun build(tool: RemoteTool, arguments: JsonObject): String {
        val schemaProperties = tool.parameters["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val unknown = arguments.keys - schemaProperties.keys
        require(unknown.isEmpty()) { "Parámetros no permitidos: ${unknown.joinToString()}" }

        val values = arguments.mapValues { (_, value) -> value.jsonPrimitive.content }
        val template = tool.transport.commandTemplate
        val required = placeholder.findAll(template).map { it.groupValues[1] }.toSet()
        val missing = required - values.keys
        require(missing.isEmpty()) { "Faltan parámetros: ${missing.joinToString()}" }

        return placeholder.replace(template) { match ->
            shellQuote(values.getValue(match.groupValues[1]))
        }
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
