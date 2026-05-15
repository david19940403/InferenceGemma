package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    val images: List<String>? = null,
    val options: ModelOptions? = null,
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
