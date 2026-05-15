package hn.fredi.inferencelocal.api.models
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
@Serializable
data class EmbedRequest(
    val model: String,
    /** input puede ser string o array; aquí lo aceptamos como string.
     *  Para batch real habría que usar JsonElement y deserializar. */
    val input: String? = null,
    /** legacy alias */
    val prompt: String? = null,
    val truncate: Boolean? = null,
    val options: ModelOptions? = null,
    @SerialName("keep_alive") val keepAlive: Long? = null
)

@Serializable
data class EmbedResponse(
    val model: String,
    /** Array de vectores (uno por input en batch) */
    val embeddings: List<List<Float>>
)

/** /api/embeddings — endpoint legacy de Ollama */
@Serializable
data class LegacyEmbeddingsRequest(
    val model: String,
    val prompt: String? = null,
    val options: ModelOptions? = null,
    @SerialName("keep_alive") val keepAlive: Long? = null
)

@Serializable
data class LegacyEmbeddingsResponse(
    val embedding: List<Float>
)