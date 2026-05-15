package hn.fredi.inferencelocal.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmbeddingsRequest(
    val model: String = "gemma-2b-it",
    val prompt: String,
    val options: ModelOptions? = null
)

@Serializable
data class EmbeddingsResponse(
    val embedding: List<Float>
)
@Serializable
data class OaiEmbeddingRequest(
    val model: String,
    val input: String? = null
)

@Serializable
data class OaiEmbeddingResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<OaiEmbeddingData>,
    val model: String,
    val usage: OaiUsage
)

@Serializable
data class OaiEmbeddingData(
    @SerialName("object") val objectType: String = "embedding",
    val index: Int,
    val embedding: List<Float>
)
