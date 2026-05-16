package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import java.util.regex.Pattern

@Serializable
data class GenerateRequest(
    val model: String,
    val prompt: String,
    val suffix: String? = null,
    val system: String? = null,
    val template: String? = null,
    val stream: Boolean? = null,        // default true en Ollama
    val raw: Boolean? = null,
    val format: JsonElement? = null,    // "json" o JSON Schema object
    val options: ModelOptions? = null,
    @SerialName("images") val images: List<String>? = null,
    @Serializable(with = DurationToSecondsSerializer::class)
    @SerialName("keep_alive") val keepAlive: Long? = null
)

/** Chunk de stream (done=false) para /api/generate */
@Serializable
data class GenerateStreamChunk(
    val model: String,
    @SerialName("created_at") val createdAt: String,
    val response: String,
    val done: Boolean
)

/** Chunk final (done=true) para /api/generate con stats de rendimiento */
@Serializable
data class GenerateFinalChunk(
    val model: String,
    @SerialName("created_at")          val createdAt: String,
    val response: String,
    val done: Boolean,
    @SerialName("done_reason")         val doneReason: String,
    @SerialName("total_duration")      val totalDuration: Long,
    @SerialName("load_duration")       val loadDuration: Long,
    @SerialName("prompt_eval_count")   val promptEvalCount: Int,
    @SerialName("prompt_eval_duration") val promptEvalDuration: Long,
    @SerialName("eval_count")          val evalCount: Int,
    @SerialName("eval_duration")       val evalDuration: Long,
    /** Context tokens (para /api/generate sin chat) */
    val context: List<Int>? = null
)

object DurationToSecondsSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DurationToSeconds", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val rawValue = decoder.decodeString().trim().lowercase()

        // Expresión regular para capturar el número y la unidad (ej. 5m, 30s, 1h)
        val matcher = Pattern.compile("^(\\d+)([smh])$").matcher(rawValue)

        if (!matcher.matches()) {
            // Si viene solo el número en string (ej. "300"), lo parseamos directo
            return rawValue.toLongOrNull() ?: throw IllegalArgumentException("Formato de duración inválido: $rawValue")
        }

        val cantidad = matcher.group(1).toLong()
        val unidad = matcher.group(2)

        return when (unidad) {
            "s" -> cantidad
            "m" -> cantidad * 60
            "h" -> cantidad * 3600
            else -> cantidad
        }
    }

    override fun serialize(encoder: Encoder, value: Long) {
        // Al serializar de vuelta, si quieres, lo mandas como string con "s"
        encoder.encodeString("${value}s")
    }
}


