package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class ShowRequest(
    val name: String,
    val verbose: Boolean? = null
)

@Serializable
data class ShowResponse(
    val modelfile: String,
    val parameters: String? = null,
    val template: String? = null,
    val details: ModelDetails? = null,
    @SerialName("model_info") val modelInfo: Map<String, String>? = null
)