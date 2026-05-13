package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelInfo(
    val name: String,
    @SerialName("modified_at") val modifiedAt: String,
    val size: Long,
    val digest: String,
    val details: ModelDetails
)

@Serializable
data class ModelDetails(
    val format: String = "gguf",
    val family: String = "gemma",
    val families: List<String>? = listOf("gemma"),
    @SerialName("parameter_size") val parameterSize: String = "2B",
    @SerialName("quantization_level") val quantizationLevel: String = "INT4"
)

@Serializable
data class TagsResponse(val models: List<ModelInfo>)