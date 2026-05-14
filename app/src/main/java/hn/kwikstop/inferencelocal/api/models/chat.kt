package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,            // "system" | "user" | "assistant"
    val content: String,
    val images: List<String>? = null   // base64, para multimodal (futuro)
)

@Serializable
data class ChatRequest(
    val model: String = "gemma-2b-it",
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val options: ModelOptions? = null
)

@Serializable
data class ChatResponse(
    val model: String,
    @SerialName("created_at") val createdAt: String,
    val message: ChatMessage,
    val done: Boolean = true,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,

    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null
)

@Serializable
data class StreamToken(
    val text    : String,
    val isDone  : Boolean
)