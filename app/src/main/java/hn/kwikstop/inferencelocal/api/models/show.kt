package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShowRequest(val name: String)

@Serializable
data class ShowResponse(
    val modelfile: String,
    val parameters: String,
    val template: String,
    val details: ModelDetails,
    val license: String? = null
)