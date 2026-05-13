package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OaiChatRequest(
    val model: String = "gemma-2b-it",
    val messages: List<OaiMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
data class OaiMessage(
    val role: String,
    val content: String
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
    val index: Int = 0,
    val message: OaiMessage,
    @SerialName("finish_reason") val finishReason: String = "stop"
)

@Serializable
data class OaiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)
