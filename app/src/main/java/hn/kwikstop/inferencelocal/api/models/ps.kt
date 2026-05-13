package hn.kwikstop.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RunningModel(
    val name: String,
    val model: String,
    val size: Long,
    val digest: String,
    val details: ModelDetails,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("size_vram") val sizeVram: Long
)

@Serializable
data class PsResponse(val models: List<RunningModel>)