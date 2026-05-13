package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GenerateRequest(
    val model: String = "gemma-2b-it",
    val prompt: String,
    val system: String? = null,
    val template: String? = null,
    val context: List<Int>? = null,
    val stream: Boolean = false,
    val raw: Boolean = false,
    val options: ModelOptions? = null
)

@Serializable
data class GenerateResponse(
    val model: String,
    val response: String,
    @SerialName("created_at") val createdAt: String,
    val done: Boolean = true,
    val context: List<Int>? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("eval_duration") val evalDuration: Long? = null
)