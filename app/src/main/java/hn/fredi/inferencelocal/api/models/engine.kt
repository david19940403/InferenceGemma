package hn.fredi.inferencelocal.api.models
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
/** Token emitido por el stream interno del engine */
data class StreamToken(
    val text: String,
    val isDone: Boolean
)

/** Mensaje de chat unificado (Ollama nativo usa este mismo shape) */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** Imágenes en base64 para modelos multimodal (opcional) */
    val images: List<String>? = null,
    /** Tool calls hechos por el asistente (opcional) */
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null
)

@Serializable
data class ToolCall(
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

/** Opciones de generación mapeadas a parámetros de Ollama */
@Serializable
data class ModelOptions(
    val temperature: Float? = null,
    @SerialName("top_k")     val topK: Int? = null,
    @SerialName("top_p")     val topP: Float? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    @SerialName("num_ctx")   val numCtx: Int? = null,
    @SerialName("repeat_penalty") val repeatPenalty: Float? = null,
    val seed: Int? = null,
    val stop: List<String>? = null
)