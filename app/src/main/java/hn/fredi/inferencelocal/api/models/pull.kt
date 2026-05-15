package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class PullRequest(
    val name: String,
    val insecure: Boolean? = null,
    val stream: Boolean? = null
)

@Serializable
data class PullResponse(
    val status: String,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null
)
