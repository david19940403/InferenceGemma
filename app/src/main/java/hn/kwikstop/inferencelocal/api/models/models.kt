package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OaiModelsResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<OaiModelData>
)

@Serializable
data class OaiModelData(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "local"
)

@Serializable
data class ModelOptions(
    val temperature: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    val seed: Int? = null
)