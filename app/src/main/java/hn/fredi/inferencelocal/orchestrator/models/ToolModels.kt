package hn.fredi.inferencelocal.orchestrator.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolSearchRequest(val query: String, @SerialName("top_k") val topK: Int = 3)

@Serializable
data class ToolSearchResponse(val tools: List<RemoteTool> = emptyList())

@Serializable
data class RemoteTool(
    val name: String,
    val description: String,
    val transport: Transport,
    val parameters: JsonObject
)

@Serializable
data class Transport(
    val type: String,
    @SerialName("target_host") val targetHost: String,
    @SerialName("command_template") val commandTemplate: String,
    val user: String = "root",
    val port: Int = 22
)

data class CommandOutput(val exitCode: Int, val stdout: String, val stderr: String)

sealed interface AgentEvent {
    data class Resolving(val query: String) : AgentEvent
    data class Thinking(val iteration: Int) : AgentEvent
    data class Executing(val tool: String, val host: String) : AgentEvent
    data class Finished(val answer: String) : AgentEvent
}
