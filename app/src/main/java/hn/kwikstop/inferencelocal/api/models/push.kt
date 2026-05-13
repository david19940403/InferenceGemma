package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushRequest(
    val name: String,
    val insecure: Boolean = false,
    val stream: Boolean = false
)

@Serializable
data class PushResponse(val status: String)