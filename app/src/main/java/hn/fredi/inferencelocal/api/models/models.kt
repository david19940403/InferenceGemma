package hn.fredi.inferencelocal.api.models

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
    @SerialName("owned_by") val ownedBy: String
)


