package hn.fredi.inferencelocal.api.tools

import hn.fredi.inferencelocal.api.models.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolManager(
    private val internalTools: List<Tool> = listOf(WebSearchTool(), UrlReaderTool())
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Genera instrucciones incluyendo herramientas internas y las proporcionadas por el cliente.
     */
    fun getSystemInstructions(clientTools: List<hn.fredi.inferencelocal.api.models.Tool>? = null): String {
        val allTools = mutableListOf<String>()
        
        // Agregar internas
        internalTools.forEach { 
            allTools.add("- ${it.name} (INTERNAL): ${it.description}. Params: ${it.parameters}")
        }
        
        // Agregar del cliente (MCP / UI)
        clientTools?.forEach { 
            allTools.add("- ${it.function.name} (CLIENT): ${it.function.description}. Params: ${it.function.parameters}")
        }

        val toolsDesc = allTools.joinToString("\n")
        
        return """
            [DYNAMIC CAPABILITIES]
            You have access to INTERNAL and CLIENT tools.
            To use any tool, respond ONLY with this JSON:
            {"tool": "name", "args": {"param": "value"}}
            
            Available Tools:
            $toolsDesc
            
            IMPORTANT: INTERNAL tools are executed by the server. CLIENT tools are executed by the user's interface. 
            Use CLIENT tools for local files, system actions or MCP integrations provided by the UI.
        """.trimIndent()
    }

    fun isInternal(name: String): Boolean = internalTools.any { it.name == name }

    fun getInternalTool(name: String): Tool? = internalTools.find { it.name == name }

    private val mcpServers = mutableMapOf<String, String>()

    fun registerMcpServer(name: String, url: String) {
        mcpServers[name] = url
    }

    fun getMcpServers(): Map<String, String> = mcpServers.toMap()

    /**
     * Detecta si el texto del modelo es un llamado a herramienta.
     * Soporta JSON puro, envuelto en bloques de código markdown, o incrustado en texto.
     */
    fun detectToolCall(text: String, clientTools: List<hn.fredi.inferencelocal.api.models.Tool>? = null): Pair<String, Map<String, Any>>? {
        // Optimización: Si no hay '{', no hay JSON
        if (!text.contains('{')) return null

        // 1. Intentar extraer de bloque de código markdown
        val markdownPattern = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
        val markdownMatch = markdownPattern.find(text)
        
        val jsonCandidate = if (markdownMatch != null) {
            markdownMatch.groupValues[1]
        } else {
            // 2. Buscar el primer '{' y el último '}'
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start != -1 && end != -1 && end > start) {
                text.substring(start, end + 1)
            } else {
                null
            }
        } ?: return null

        return try {
            val obj = json.parseToJsonElement(jsonCandidate).jsonObject
            val toolName = obj["tool"]?.jsonPrimitive?.content ?: return null
            
            // Extraer argumentos de forma robusta
            val args = mutableMapOf<String, Any>()
            
            // 1. Buscar en el objeto "args" (estándar)
            obj["args"]?.jsonObject?.forEach { (k, v) ->
                val prim = v.jsonPrimitive
                args[k] = if (prim.isString) prim.content else prim.toString()
            }
            
            // 2. Fallback: buscar en el nivel superior (si el modelo omitió "args")
            obj.forEach { (k, v) ->
                if (k != "tool" && k != "args" && !args.containsKey(k)) {
                    val prim = v.jsonPrimitive
                    args[k] = if (prim.isString) prim.content else prim.toString()
                }
            }
            
            // Validar que la herramienta exista (interna o cliente)
            val exists = internalTools.any { it.name == toolName } || clientTools?.any { it.function.name == toolName } == true
            if (!exists) return null
            
            android.util.Log.i("ToolManager", "Detectada herramienta: $toolName con args: $args")
            toolName to args
        } catch (e: Exception) {
            android.util.Log.e("ToolManager", "Error parseando JSON de herramienta: ${e.message}")
            null
        }
    }

    /**
     * Fallback para comandos manuales (mantenemos compatibilidad).
     */
    suspend fun processManualCommands(
        messages: List<ChatMessage>,
        options: Map<String, Any> = emptyMap()
    ): List<ChatMessage> {
        val lastMsg = messages.lastOrNull() ?: return messages
        if (lastMsg.role != "user") return messages
        val content = lastMsg.content

        val manualTool = internalTools.find { it.shouldActivate(content) } ?: return messages
        
        val argName = if (manualTool.name == "web_search") "query" else "url"
        val cleanInput = content.replace(Regex("^(search|read|busca):", RegexOption.IGNORE_CASE), "").trim()
        
        val result = manualTool.call(mapOf(argName to cleanInput), options)
        
        val newMessages = messages.toMutableList()
        newMessages[newMessages.size - 1] = lastMsg.copy(content = """
            [CONTEXTO OBTENIDO]: $result
            ---
            Petición: $cleanInput
        """.trimIndent())
        
        return newMessages
    }
}
