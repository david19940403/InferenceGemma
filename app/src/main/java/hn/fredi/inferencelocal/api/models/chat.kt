package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean? = null,        // default true en Ollama
    val format: JsonElement? = null,
    val options: ModelOptions? = null,
    val tools: List<Tool>? = null,
    @SerialName("keep_alive") val keepAlive: Long? = null
)

/** Shape del mensaje dentro de respuestas chat de Ollama */
@Serializable
data class OllamaMessage(
    val role: String,
    val content: String,
    val images: List<String>? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

/** Chunk de stream (done=false) para /api/chat */
@Serializable
data class ChatStreamChunk(
    val model: String,
    @SerialName("created_at") val createdAt: String,
    val message: OllamaMessage,
    val done: Boolean,
    @SerialName("done_reason") val doneReason: String? = null
)

/** Objeto único no-stream O chunk final de stream (done=true) */
@Serializable
data class ChatFinalResponse(
    val model: String,
    @SerialName("created_at")          val createdAt: String,
    val message: OllamaMessage,
    val done: Boolean,
    @SerialName("done_reason")         val doneReason: String,
    @SerialName("total_duration")      val totalDuration: Long,
    @SerialName("load_duration")       val loadDuration: Long,
    @SerialName("prompt_eval_count")   val promptEvalCount: Int,
    @SerialName("prompt_eval_duration") val promptEvalDuration: Long,
    @SerialName("eval_count")          val evalCount: Int,
    @SerialName("eval_duration")       val evalDuration: Long
)

/** Respuesta especial cuando messages=[] (load/unload model) */
@Serializable
data class ChatUnloadResponse(
    val model: String,
    @SerialName("created_at")  val createdAt: String,
    val message: OllamaMessage,
    @SerialName("done_reason") val doneReason: String,
    val done: Boolean
)
