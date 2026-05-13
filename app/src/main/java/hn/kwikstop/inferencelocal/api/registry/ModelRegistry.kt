package hn.kwikstop.inferencelocal.api.registry


import android.util.Log
import hn.kwikstop.inferencelocal.api.models.*
import hn.kwikstop.inferencelocal.api.models.ModelDetails
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * ModelRegistry
 *
 * Gestión en memoria del catálogo de modelos instalados.
 * Simula el comportamiento del registro de Ollama para los endpoints
 * de gestión (/api/tags, /api/show, /api/create, /api/copy, /api/delete).
 *
 * En un dispositivo real, los modelos son archivos .bin en el directorio
 * de datos externo de la app. Esta clase indexa esos archivos y mantiene
 * metadatos adicionales (alias, modelfiles) en memoria.
 */
class ModelRegistry(
    private val modelsDir    : File,
    private val modelFileName: String = DEFAULT_FILENAME  // ← parámetro nuevo
) {


    companion object {
        private const val TAG = "ModelRegistry"

        // Modelo físico que siempre está presente si el archivo existe
        const val DEFAULT_MODEL    = "gemma-2b-it"
        const val DEFAULT_FILENAME = "gemma-2b-it-gpu-int4.bin"

        private const val OLLAMA_SERVER_VERSION = "0.3.12"  // versión emulada
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private val mutex = Mutex()

    /**
     * Mapa de nombre → metadatos de modelo.
     * Se inicializa con el modelo físico si el archivo existe.
     */
    private val catalog = mutableMapOf<String, ModelEntry>()

    // Modelos actualmente "cargados en memoria" (sólo el activo real)
    private var activeModelName: String? = null
    private var modelLoadedAt: Instant = Instant.now()

    // ─────────────────────────────────────────────────────────────────────────
    // Inicialización
    // ─────────────────────────────────────────────────────────────────────────

    /** Escanea el directorio de modelos y construye el catálogo inicial. */
    fun initialize() {
        val physicalFile = File(modelsDir, modelFileName)
        if (physicalFile.exists()) {
            catalog[DEFAULT_MODEL] = ModelEntry(
                name        = DEFAULT_MODEL,
                filePath    = physicalFile.absolutePath,
                size        = physicalFile.length(),
                digest      = "sha256:${physicalFile.name.hashCode().toUInt().toString(16).padStart(64, '0')}",
                modifiedAt  = Instant.ofEpochMilli(physicalFile.lastModified()),
                modelfile   = defaultModelfile(DEFAULT_MODEL),
                parameters  = defaultParameters(),
                template    = defaultTemplate(),
                details     = ModelDetails(
                    format             = "litert",
                    family             = "gemma",
                    families           = listOf("gemma"),
                    parameterSize      = "2B",
                    quantizationLevel  = "INT4"
                )
            )
            Log.i(TAG, "Modelo registrado: $DEFAULT_MODEL (${physicalFile.length() / 1_048_576} MB)")
        } else {
            Log.w(TAG, "Archivo del modelo no encontrado: ${physicalFile.absolutePath}")
        }
    }

    fun setActiveModel(name: String) {
        activeModelName = name
        modelLoadedAt   = Instant.now()
    }

    fun clearActiveModel() {
        activeModelName = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    /** Lista todos los modelos instalados → /api/tags */
    suspend fun listModels(): TagsResponse = mutex.withLock {
        val models = catalog.values.map { entry ->
            ModelInfo(
                name       = entry.name,
                modifiedAt = DateTimeFormatter.ISO_INSTANT.format(entry.modifiedAt),
                size       = entry.size,
                digest     = entry.digest,
                details    = entry.details
            )
        }
        TagsResponse(models = models)
    }

    /** Devuelve detalles de un modelo → /api/show */
    suspend fun showModel(name: String): ShowResponse? = mutex.withLock {
        val entry = catalog[normalizedName(name)] ?: return@withLock null
        ShowResponse(
            modelfile  = entry.modelfile,
            parameters = entry.parameters,
            template   = entry.template,
            details    = entry.details,
            license    = "Apache 2.0 / Gemma Terms of Use"
        )
    }

    /** Crea un alias con un Modelfile personalizado → /api/create */
    suspend fun createModel(name: String, modelfile: String): CreateResponse = mutex.withLock {
        // Parsear FROM del Modelfile para encontrar el modelo base
        val fromLine = modelfile.lines()
            .firstOrNull { it.trim().startsWith("FROM", ignoreCase = true) }
            ?.substringAfter("FROM")?.trim()
            ?: return@withLock CreateResponse(status = "error: Modelfile must contain a FROM instruction")

        val base = catalog[normalizedName(fromLine)]
            ?: return@withLock CreateResponse(status = "error: base model '$fromLine' not found")

        catalog[normalizedName(name)] = base.copy(
            name      = normalizedName(name),
            modelfile = modelfile,
            modifiedAt = Instant.now()
        )
        Log.i(TAG, "Modelo creado: $name (base: $fromLine)")
        CreateResponse(status = "success")
    }

    /** Copia un modelo a otro nombre → /api/copy */
    suspend fun copyModel(source: String, destination: String): Boolean = mutex.withLock {
        val entry = catalog[normalizedName(source)] ?: return@withLock false
        catalog[normalizedName(destination)] = entry.copy(
            name       = normalizedName(destination),
            modifiedAt = Instant.now()
        )
        Log.i(TAG, "Modelo copiado: $source → $destination")
        true
    }

    /** Elimina un modelo → /api/delete */
    suspend fun deleteModel(name: String): Boolean = mutex.withLock {
        val norm = normalizedName(name)
        // No permitir eliminar el modelo base físico mientras está activo
        if (norm == activeModelName) return@withLock false
        catalog.remove(norm) != null
    }

    /** Simula una descarga (pull) → /api/pull */
    fun knownRemoteModels(): List<String> = listOf(
        "gemma-2b-it", "gemma-7b-it", "gemma2-9b", "phi3", "llama3.2"
    )

    /** Lista modelos activos en memoria → /api/ps */
    fun listRunningModels(): PsResponse {
        val name = activeModelName ?: return PsResponse(models = emptyList())
        val entry = catalog[name] ?: return PsResponse(models = emptyList())
        val expiry = modelLoadedAt.plusSeconds(5 * 60)   // TTL simulado: 5 min

        return PsResponse(
            models = listOf(
                RunningModel(
                    name      = entry.name,
                    model     = entry.name,
                    size      = entry.size,
                    digest    = entry.digest,
                    details   = entry.details,
                    expiresAt = DateTimeFormatter.ISO_INSTANT.format(expiry),
                    sizeVram  = entry.size   // En GPU, usamos el tamaño del archivo como aproximación
                )
            )
        )
    }

    /** Versión emulada del servidor → /api/version */
    fun version(): VersionResponse = VersionResponse(version = OLLAMA_SERVER_VERSION)

    /** Comprueba si un modelo existe en el catálogo. */
    fun exists(name: String): Boolean = catalog.containsKey(normalizedName(name))

    /** Devuelve la ruta física del modelo. */
    fun filePath(name: String): String? = catalog[normalizedName(name)]?.filePath

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun normalizedName(name: String): String =
        if (':' in name) name.lowercase() else "${name.lowercase()}:latest"
            .replace(Regex(":latest$"), "")   // gemma-2b-it siempre sin tag

    private fun defaultModelfile(name: String) = """
        FROM $name
        PARAMETER temperature 0.8
        PARAMETER top_k 40
        PARAMETER top_p 0.9
        PARAMETER num_predict 512
        SYSTEM You are a helpful AI assistant running locally on Android.
    """.trimIndent()

    private fun defaultParameters() = """
        temperature    0.8
        top_k          40
        top_p          0.9
        num_predict    512
    """.trimIndent()

    private fun defaultTemplate() = """
        <start_of_turn>user
        {{ .Prompt }}<end_of_turn>
        <start_of_turn>model
    """.trimIndent()
}

// ─────────────────────────────────────────────────────────────────────────────
// Entrada interna del catálogo
// ─────────────────────────────────────────────────────────────────────────────

data class ModelEntry(
    val name: String,
    val filePath: String,
    val size: Long,
    val digest: String,
    val modifiedAt: Instant,
    val modelfile: String,
    val parameters: String,
    val template: String,
    val details: ModelDetails
)