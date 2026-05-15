package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RunningModelList(val models: List<RunningModelInfo>)

@Serializable
data class RunningModelInfo(
    val name: String,
    val model: String,
    val size: Long,
    val digest: String,
    val details: ModelDetails? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("size_vram") val sizeVram: Long? = null
)
@Serializable
data class OllamaError(val error: String)