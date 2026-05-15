package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class CreateRequest(
    val name: String,
    val modelfile: String? = null,
    val stream: Boolean? = null,
    val path: String? = null
)

@Serializable
data class CreateResult(val status: String)

/** Línea del stream de progreso de /api/create y /api/pull */
@Serializable
data class CreateStatusLine(val status: String)