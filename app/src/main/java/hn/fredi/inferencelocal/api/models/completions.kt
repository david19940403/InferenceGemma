package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement


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
data class OaiChatMessage(
    val role: String,
    val content: OaiContent? = null
)
@Serializable
data class OaiContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OaiImageUrl? = null
)

@Serializable
data class OaiImageUrl(val url: String)

@Serializable(with = OaiContentSerializer::class)
sealed class OaiContent {
    data class Text(val value: String) : OaiContent()
    data class Parts(val parts: List<OaiContentPart>) : OaiContent()
}

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

object OaiContentSerializer : KSerializer<OaiContent> {
    override val descriptor = buildClassSerialDescriptor("OaiContent")

    override fun deserialize(decoder: Decoder): OaiContent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonPrimitive -> OaiContent.Text(element.content)
            element is JsonArray -> OaiContent.Parts(
                Json.decodeFromJsonElement(element)
            )
            else -> OaiContent.Text("")
        }
    }

    override fun serialize(encoder: Encoder, value: OaiContent) {
        when (value) {
            is OaiContent.Text -> encoder.encodeString(value.value)
            is OaiContent.Parts -> { /* no needed */ }
        }
    }
}