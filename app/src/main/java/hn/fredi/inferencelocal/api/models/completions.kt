package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class OaiCompletionRequest(
    val model: String,
    val prompt: String? = null,
    val stream: Boolean? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
data class OaiCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<OaiCompletionChoice>,
    val usage: OaiUsage
)

@Serializable
data class OaiCompletionChoice(
    val text: String,
    val index: Int,
    @SerialName("finish_reason") val finishReason: String?
)




@Serializable
data class OaiUsage(
    @SerialName("prompt_tokens")     val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens")      val totalTokens: Int = 0
)

@Serializable
data class OaiStreamChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<OaiStreamChoice>
)

@Serializable
data class OaiStreamChoice(
    val index: Int,
    val delta: OaiDelta,
    @SerialName("finish_reason") val finishReason: String?
)

@Serializable
data class OaiDelta(
    val role: String? = null,
    val content: String? = null
)
@Serializable
data class OaiChatRequest(
    val model: String,
    val messages: List<OaiMessage>,
    val stream: Boolean? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens")  val maxTokens: Int? = null,
    @SerialName("top_p")       val topP: Float? = null,
    val stop: List<String>? = null,
    val tools: List<Tool>? = null
)

@Serializable
data class OaiMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null
)

@Serializable
data class OaiChatResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OaiChoice>,
    val usage: OaiUsage
)

@Serializable
data class OaiChoice(
    val index: Int,
    val message: OaiMessage,
    @SerialName("finish_reason") val finishReason: String?
)