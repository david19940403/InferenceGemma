package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelList(val models: List<ModelInfo>)

@Serializable
data class ModelInfo(
    val name: String,
    val model: String? = null,
    @SerialName("modified_at") val modifiedAt: String,
    val size: Long,
    val digest: String,
    val details: ModelDetails? = null,
    @SerialName("remote_model") val remoteModel: String? = null,
    @SerialName("remote_host") val remoteHost: String? = null
)

@Serializable
data class ModelDetails(
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null
)