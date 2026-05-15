package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class ApiError(val error: String)