package hn.fredi.inferencelocal.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64

@Serializable
data class Transport(val type: String, val target_host: String, val command_template: String)
@Serializable
data class RemoteTool(val name: String, val description: String, val transport: Transport, val parameters: JsonObject)
@Serializable
data class HostConfig(val host: String, val user: String, val port: Int = 22, val shell: String = "posix")
@Serializable
data class AgentConfig(
    val tool_store_url: String,
    val token: String = "",
    val model_path: String,
    val backend: String = "NPU",
    val private_key: String,
    val known_hosts: String,
    val hosts: List<HostConfig>
) {
    fun validate() {
        val uri = java.net.URI(tool_store_url)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "URL Tool Store inválida" }
        require(backend in listOf("NPU", "GPU", "CPU")) { "Backend inválido" }
        require(private_key.contains("PRIVATE KEY") && known_hosts.isNotBlank()) { "Faltan clave SSH o known_hosts" }
        require(hosts.isNotEmpty() && hosts.map { it.host }.distinct().size == hosts.size) { "Hosts vacíos o duplicados" }
        hosts.forEach { require(it.user.matches(Regex("[a-zA-Z_][a-zA-Z0-9_-]*")) && it.port in 1..65535 && it.shell in listOf("posix", "powershell")) { "Configuración de host inválida" } }
    }
}

object GuidedDecodingValidator {
    fun parse(text: String, tools: List<RemoteTool>): JsonObject {
        val obj = Json.parseToJsonElement(text.trim()).jsonObject
        require(obj.size == 1) { "El modelo debe emitir una única acción JSON" }
        if ("final_answer" in obj) {
            require(obj.getValue("final_answer").jsonPrimitive.isString && obj.getValue("final_answer").jsonPrimitive.content.isNotBlank())
        } else {
            val call = obj.getValue("tool_call").jsonObject
            require(call.keys == setOf("name", "arguments"))
            val tool = tools.singleOrNull { it.name == call.getValue("name").jsonPrimitive.content }
                ?: error("Herramienta fuera del catálogo recuperado")
            if (tool.name == ToolCatalogBudget.SEARCH && tool.transport.type == "internal") {
                val args = call.getValue("arguments").jsonObject
                require(args.keys == setOf("query")) { "La búsqueda requiere solo query" }
                val query = args.getValue("query").jsonPrimitive
                require(query.isString && query.content.isNotBlank() && query.content.toByteArray().size <= 512 && query.content.none { it.isISOControl() }) { "Consulta de herramientas inválida" }
            } else arguments(tool, call.getValue("arguments").jsonObject)
        }
        return obj
    }

    fun arguments(tool: RemoteTool, args: JsonObject): Map<String, String> {
        require(tool.transport.type == "ssh")
        val props = tool.parameters["properties"]?.jsonObject ?: error("Falta properties")
        val required = tool.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        require(args.keys.all { it in props } && required.all { it in args }) { "Parámetros desconocidos o requeridos ausentes" }
        return props.mapValues { (name, raw) ->
            val schema = raw.jsonObject
            require(schema["type"]?.jsonPrimitive?.content == "string") { "Solo se admiten parámetros string" }
            val value = args[name] ?: schema["default"] ?: JsonPrimitive("")
            require(value is JsonPrimitive && value.isString) { "Tipo de parámetro inválido" }
            val text = value.content
            require(text.length <= 2048 && text.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Parámetro demasiado largo o con controles" }
            schema["enum"]?.jsonArray?.let { require(value in it) { "Valor fuera del enum" } }
            text
        }
    }
}

object CommandBuilder {
    private val token = Regex("\"[^\"]*\"|'[^']*'|[^\\s]+")
    fun build(tool: RemoteTool, args: JsonObject, shell: String): String {
        val values = GuidedDecodingValidator.arguments(tool, args)
        val template = tool.transport.command_template
        require(template.length <= 2000)
        val matches = token.findAll(template).toList()
        require(matches.isNotEmpty())
        val parts = matches.map { match ->
            val raw = match.value
            val value = if (raw.first() in listOf('\'', '"')) raw.substring(1, raw.length - 1) else raw
            val placeholder = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}").matchEntire(value)
            if (placeholder != null) values[placeholder.groupValues[1]] ?: error("Placeholder sin esquema")
            else { require(value.matches(Regex("[a-zA-Z0-9_./:\\\\=+-]+"))) { "La plantilla admite solo ejecutable y argumentos literales o placeholders completos" }; value }
        }
        return when (shell) {
            "posix" -> parts.joinToString(" ") { "'" + it.replace("'", "'\"'\"'") + "'" }
            "powershell" -> {
                val script = "& " + parts.joinToString(" ") { "'" + it.replace("'", "''") + "'" } + "; if (\$null -ne \$LASTEXITCODE) { exit \$LASTEXITCODE }"
                "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand " + Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            }
            else -> error("Shell desconocido")
        }
    }
}
