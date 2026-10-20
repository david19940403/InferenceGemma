package hn.fredi.inferencelocal.orchestrator.worker

import hn.fredi.inferencelocal.orchestrator.engine.InferenceGateway
import hn.fredi.inferencelocal.orchestrator.models.AgentEvent
import hn.fredi.inferencelocal.orchestrator.models.RemoteTool
import hn.fredi.inferencelocal.orchestrator.network.SSHConnectionPool
import hn.fredi.inferencelocal.orchestrator.resolver.ToolStoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgenticLoopWorker(
    private val toolStore: ToolStoreClient,
    private val inference: InferenceGateway,
    private val ssh: SSHConnectionPool,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxIterations: Int = 4
) {
    fun process(userQuery: String): Flow<AgentEvent> = flow {
        require(userQuery.isNotBlank()) { "Escribe una instrucción" }
        emit(AgentEvent.Resolving(userQuery))
        val tools = toolStore.getToolsForQuery(userQuery, 3)
        check(tools.isNotEmpty()) { "No se encontraron herramientas compatibles" }
        var observation: String? = null

        repeat(maxIterations) { index ->
            emit(AgentEvent.Thinking(index + 1))
            val raw = inference.generate(buildPrompt(userQuery, tools, observation))
            val response = parseObject(raw)
            response["final_answer"]?.jsonPrimitive?.content?.let {
                emit(AgentEvent.Finished(it))
                return@flow
            }
            val call = response["tool_call"]?.jsonObject
                ?: error("El modelo no devolvió tool_call ni final_answer")
            val name = call["name"]?.jsonPrimitive?.content ?: error("tool_call.name es obligatorio")
            val args = call["arguments"]?.jsonObject ?: JsonObject(emptyMap())
            val selected = tools.firstOrNull { it.name == name }
                ?: error("El modelo seleccionó una herramienta no autorizada: $name")
            check(selected.transport.type == "ssh") { "Transporte no soportado: ${selected.transport.type}" }
            emit(AgentEvent.Executing(selected.name, selected.transport.targetHost))
            val output = ssh.executeCommand(
                host = selected.transport.targetHost,
                user = selected.transport.user,
                port = selected.transport.port,
                command = CommandTemplate.build(selected, args)
            )
            observation = json.encodeToString(
                JsonObject(mapOf(
                    "exit_code" to kotlinx.serialization.json.JsonPrimitive(output.exitCode),
                    "stdout" to kotlinx.serialization.json.JsonPrimitive(output.stdout),
                    "stderr" to kotlinx.serialization.json.JsonPrimitive(output.stderr)
                ))
            )
        }
        error("La tarea excedió el límite de $maxIterations iteraciones")
    }.flowOn(Dispatchers.Default)

    private fun buildPrompt(query: String, tools: List<RemoteTool>, observation: String?): String = """
        Instrucción: $query
        Herramientas permitidas: ${json.encodeToString(tools)}
        ${observation?.let { "Resultado anterior: $it" } ?: "No hay resultado anterior."}

        Devuelve solo uno de estos objetos JSON, sin Markdown:
        {"tool_call":{"name":"nombre_exacto","arguments":{}}}
        {"final_answer":"respuesta breve para el usuario"}
        Usa únicamente una herramienta listada. Tras recibir un resultado, evalúalo antes de finalizar.
    """.trimIndent()

    private fun parseObject(raw: String): JsonObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "Respuesta de inferencia no es JSON" }
        return json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
    }
}
