package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/** Línea NDJSON del stream de pull/push */
@Serializable
data class PullStatusLine(
    val status: String,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null
)

@Serializable
data class PushRequest(val name: String)