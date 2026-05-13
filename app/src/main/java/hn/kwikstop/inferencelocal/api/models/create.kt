package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateRequest(
    val name: String,
    val modelfile: String,
    val stream: Boolean = false
)

@Serializable
data class CreateResponse(
    val status: String   // "success" | "error: ..."
)