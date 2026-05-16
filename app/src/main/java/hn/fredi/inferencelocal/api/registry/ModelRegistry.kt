package hn.fredi.inferencelocal.api.registry

import android.util.Log
import hn.fredi.inferencelocal.api.models.CreateResult
import hn.fredi.inferencelocal.api.models.ModelDetails
import hn.fredi.inferencelocal.api.models.ModelInfo
import hn.fredi.inferencelocal.api.models.ModelList
import hn.fredi.inferencelocal.api.models.RunningModelInfo
import hn.fredi.inferencelocal.api.models.RunningModelList
import hn.fredi.inferencelocal.api.models.ShowResponse
import hn.fredi.inferencelocal.api.models.VersionResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * ModelRegistry
 *
 * Gestiona el catálogo de modelos disponibles en el dispositivo,
 * replicando fielmente el comportamiento del registro de Ollama.
 *
 * DISEÑO:
 * ───────
 * • Al inicializar, escanea [modelsDir] buscando archivos .bin / .task / .gguf.
 *   Cada archivo se registra como un modelo con nombre derivado del filename.
 * • También acepta un [primaryModelPath] explícito para el modelo principal
 *   (el que MediaPipe carga como LlmInference).
 * • Los modelos creados vía /api/create son aliases en memoria sobre un base.
 * • Los nombres se normalizan: minúsculas, sin tag ":latest" implícito
 *   para modelos locales (igual que Ollama trata los modelos sin tag).
 *
 * SHAPES: todos los métodos retornan los tipos de Models.kt exactamente
 * como los define la API de Ollama.
 */
class ModelRegistry(
    /** Directorio donde están los archivos de modelo en el dispositivo */
    private val modelsDir: File
) {

    companion object {
        private const val TAG = "ModelRegistry"

        const val DEFAULT_PRIMARY_NAME    = "gemma-2b-it"
        private const val EMULATED_VERSION = "0.3.12"

        /** Extensiones de archivo que se reconocen como modelos */
        private val MODEL_EXTENSIONS = setOf("bin", "task", "gguf", "ggml", "litertlm")

        /** TTL simulado en memoria (keep_alive default de Ollama: 5 min) */
        private const val KEEP_ALIVE_SECONDS = 5 * 60L
    }

    // ─── Estado interno ───────────────────────────────────────────────────────

    private val mutex = Mutex()
    private val catalog = mutableMapOf<String, ModelEntry>()

    private var activeModelName: String? = null
    private var modelLoadedAt: Instant   = Instant.now()

    // ─────────────────────────────────────────────────────────────────────────
    // Inicialización
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Escanea [modelsDir] y registra todos los archivos de modelo encontrados.
     * Registra tanto el nombre interpretado como el nombre de archivo original como alias.
     */
    suspend fun initialize() = mutex.withLock {
        catalog.clear()
        if (!modelsDir.exists()) {
            Log.w(TAG, "Directorio de modelos no existe: ${modelsDir.absolutePath}")
            return@withLock
        }

        val files = modelsDir.listFiles { f ->
            f.isFile && f.extension.lowercase() in MODEL_EXTENSIONS
        } ?: emptyArray()

        files.forEach { file ->
            val prettyName = modelNameFromFile(file)
            val entry = buildEntry(prettyName, file)
            
            // 1. Registro con nombre interpretado (ej: gemma2:2b)
            val normPretty = normalize(prettyName)
            catalog[normPretty] = entry
            
            // 2. Registro con nombre de archivo original como ALIAS (ej: gemma-4-e2b-it)
            // Esto permite cargar el modelo usando su nombre real si la interpretación falla
            val fileNameAlias = file.nameWithoutExtension.lowercase()
            if (fileNameAlias != normPretty) {
                // Marcamos como alias pero mantenemos la referencia al archivo
                catalog[fileNameAlias] = entry.copy(name = fileNameAlias, isAlias = true)
            }
            
            Log.i(TAG, "Modelo registrado: $prettyName (alias: $fileNameAlias) [${file.name}]")
        }

        Log.i(TAG, "Catálogo inicializado: ${catalog.size} entradas")
    }

    /**
     * Registra el modelo activo en memoria (llamar desde el engine tras load()).
     * Esto actualiza /api/ps correctamente.
     */
    fun setActiveModel(name: String) {
        activeModelName = normalize(name)
        modelLoadedAt   = Instant.now()
        Log.d(TAG, "Modelo activo: $activeModelName")
    }

    fun clearActiveModel() {
        activeModelName = null
        Log.d(TAG, "Modelo activo limpiado")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Endpoints de consulta
    // ─────────────────────────────────────────────────────────────────────────

    /** /api/tags — Lista todos los modelos instalados */
    suspend fun listModels(): ModelList = mutex.withLock {
        ModelList(
            models = catalog.values
                .filter { !it.isAlias || it.modelfile.contains("FROM") } // Mostrar solo nombres reales o modelos creados manualmente
                .distinctBy { it.digest } // Evitar duplicados si hay múltiples nombres para el mismo archivo
                .sortedByDescending { it.modifiedAt }
                .map { it.toModelInfo() }
        )
    }

    /** /api/show — Detalles de un modelo (Modelfile, parámetros, template) */
    suspend fun showModel(name: String): ShowResponse? = mutex.withLock {
        val entry = catalog[normalize(name)] ?: return@withLock null
        ShowResponse(
            modelfile = entry.modelfile,
            parameters = entry.parameters,
            template = entry.template,
            details = entry.details,
            modelInfo = mapOf(
                "general.architecture" to (entry.details.family ?: "unknown"),
                "general.file_type" to (entry.details.format ?: "unknown"),
                "general.quantization_version" to (entry.details.quantizationLevel ?: "unknown")
            )
        )
    }

    /** /api/ps — Modelos actualmente cargados en memoria */
    fun listRunningModels(): RunningModelList {
        val name  = activeModelName ?: return RunningModelList(models = emptyList())
        val entry = catalog[name]    ?: return RunningModelList(models = emptyList())
        val expiry = modelLoadedAt.plusSeconds(KEEP_ALIVE_SECONDS)

        return RunningModelList(
            models = listOf(
                RunningModelInfo(
                    name = entry.name,
                    model = entry.name,
                    size = entry.size,
                    digest = entry.digest,
                    details = entry.details,
                    expiresAt = isoFormat(expiry),
                    sizeVram = entry.size   // Aproximación: mismo tamaño en VRAM
                )
            )
        )
    }

    /** /api/version */
    fun version(): VersionResponse = VersionResponse(version = EMULATED_VERSION)

    // ─────────────────────────────────────────────────────────────────────────
    // Endpoints de gestión
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * /api/create — Crea un alias con Modelfile personalizado.
     *
     * El Modelfile debe contener al menos:
     *   FROM <nombre-modelo-base>
     *
     * Cualquier modelo del catálogo puede ser base.
     */
    suspend fun createModel(name: String, modelfile: String?): CreateResult = mutex.withLock {
        if (modelfile.isNullOrBlank()) {
            return@withLock CreateResult(status = "error: modelfile is required")
        }

        // Extraer instrucción FROM
        val fromValue = modelfile.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("FROM", ignoreCase = true) }
            ?.removePrefix("FROM")?.removePrefix("from")
            ?.trim()

        if (fromValue.isNullOrBlank()) {
            return@withLock CreateResult(status = "error: Modelfile must contain a FROM instruction")
        }

        val base = catalog[normalize(fromValue)]
            ?: return@withLock CreateResult(status = "error: base model '$fromValue' not found")

        val newName = normalize(name)

        // Parsear parámetros del Modelfile para sobreescribir los del base
        val parsedParams = parseModelfileParams(modelfile)
        val parsedSystem = parseModelfileSystem(modelfile)

        catalog[newName] = base.copy(
            name      = newName,
            modelfile = modelfile,
            parameters = parsedParams.ifBlank { base.parameters },
            template  = parseModelfileTemplate(modelfile).ifBlank { base.template },
            systemPrompt = parsedSystem.ifBlank { base.systemPrompt },
            modifiedAt = Instant.now(),
            isAlias   = true
        )

        Log.i(TAG, "Alias creado: $newName → $fromValue")
        CreateResult(status = "success")
    }

    /** /api/copy — Copia un modelo con otro nombre */
    suspend fun copyModel(source: String, destination: String): Boolean = mutex.withLock {
        val entry = catalog[normalize(source)] ?: return@withLock false
        catalog[normalize(destination)] = entry.copy(
            name       = normalize(destination),
            modifiedAt = Instant.now()
        )
        Log.i(TAG, "Copiado: $source → $destination")
        true
    }

    /**
     * /api/delete — Elimina un modelo del catálogo.
     * No se puede eliminar el modelo actualmente cargado en el engine,
     * ni los modelos físicos (solo aliases).
     */
    suspend fun deleteModel(name: String): Boolean = mutex.withLock {
        val norm  = normalize(name)

        // Bloquear si está activo en memoria
        if (norm == activeModelName) {
            Log.w(TAG, "No se puede eliminar el modelo activo: $norm")
            return@withLock false
        }

        val entry = catalog[norm] ?: return@withLock false

        // Modelos físicos no se eliminan del disco, solo del catálogo si son alias
        if (!entry.isAlias) {
            Log.w(TAG, "No se puede eliminar un modelo físico desde la API: $norm")
            return@withLock false
        }

        catalog.remove(norm)
        Log.i(TAG, "Eliminado del catálogo: $norm")
        true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers para el router
    // ─────────────────────────────────────────────────────────────────────────

    /** Verifica si el nombre existe en el catálogo */
    fun exists(name: String): Boolean = catalog.containsKey(normalize(name))

    /** Ruta física del archivo del modelo (para el engine) con búsqueda flexible */
    fun filePath(name: String): String? {
        val norm = normalize(name)
        // 1. Intento exacto (nombre interpretado o alias de nombre de archivo)
        catalog[norm]?.let { return it.filePath }
        
        // 2. Búsqueda por nombre de archivo exacto (por si el normalize lo alteró)
        val rawName = name.lowercase().trim()
        catalog[rawName]?.let { return it.filePath }

        // 3. Búsqueda flexible (si pide gemma:2b y tenemos gemma2:2b)
        val baseName = norm.split(":")[0]
        val match = catalog.values.find { 
            !it.isAlias && (it.name.startsWith(baseName) || norm.startsWith(it.name.split(":")[0]))
        }
        return match?.filePath
    }

    /** Sistema prompt del modelo (para inyectar en el engine si se desea) */
    fun systemPrompt(name: String): String? = catalog[normalize(name)]?.systemPrompt

    /** Modelos remotos conocidos (para /api/pull) */
    fun knownRemoteModels(): List<String> = listOf(
        "gemma-2b-it", "gemma-7b-it", "gemma2-9b",
        "gemma3:1b", "gemma3:4b", "gemma3:12b",
        "phi3", "phi3.5",
        "llama3.2", "llama3.2:1b", "llama3.2:3b",
        "llama3.1:8b",
        "qwen2.5:0.5b", "qwen2.5:1.5b", "qwen2.5:3b",
        "mistral", "mistral:7b"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Normalización de nombres (idéntico a Ollama)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ollama normaliza los nombres a minúsculas.
     * Si el cliente manda "gemma-2b-it:latest", lo tratamos igual que "gemma-2b-it".
     * Si manda "gemma3:4b", se mantiene el tag porque es parte del identificador.
     */
    private fun normalize(name: String): String {
        val lower = name.lowercase().trim()
        // Eliminar ":latest" solo si es el tag por defecto implícito
        return if (lower.endsWith(":latest")) lower.removeSuffix(":latest") else lower
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construcción de entradas
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildEntry(name: String, file: File): ModelEntry {
        val family = detectFamily(name)
        return ModelEntry(
            name         = name,
            filePath     = file.absolutePath,
            size         = file.length(),
            digest       = computeDigest(file),
            modifiedAt   = Instant.ofEpochMilli(file.lastModified()),
            modelfile    = buildDefaultModelfile(name, family),
            parameters   = buildDefaultParameters(),
            template     = buildTemplate(family),
            systemPrompt = "You are a helpful AI assistant running locally on Android.",
            details      = buildDetails(name, file, family),
            isAlias      = false
        )
    }

    /**
     * Deriva el nombre del modelo a partir del nombre del archivo.
     * Ejemplos:
     *   gemma-2b-it-gpu-int4.bin  → gemma-2b-it
     *   gemma3-4b-q4.task         → gemma3:4b  (si tiene indicador de variante)
     *   mistral-7b-instruct.gguf  → mistral:7b
     */
    private fun modelNameFromFile(file: File): String {
        val stem = file.nameWithoutExtension.lowercase().replace("_", "-")

        // 1. Detectar familia y versión prioritariamente
        // Buscamos gemma-2, gemma-3, gemma-4 (cuantizado), etc.
        val familyMatch = Regex("(gemma-?[2-4](?!b)|gemma-?3|gemma|llama-?[3-9]\\.?\\d*|phi-?[3-4]\\.?\\d*|qwen-?2\\.5|mistral)").find(stem)
        val familyPart = familyMatch?.value ?: ""
        
        // Normalizar nombre de familia
        val familyName = when {
            familyPart.contains("gemma-2") || familyPart == "gemma2" -> "gemma2"
            familyPart.contains("gemma-3") || familyPart == "gemma3" -> "gemma3"
            familyPart.contains("gemma-4") || familyPart == "gemma4" -> "gemma-4" // gemma-4 suele ser gemma2 cuantizado a 4 bits
            familyPart.contains("gemma") -> "gemma"
            else -> familyPart.replace("-", "")
        }

        // 2. Intentar detectar tamaño de parámetros, permitiendo prefijos como 'e' (ej: e2b, 2b, 7b)
        val sizeMatch = Regex("([e]?\\d+\\.?\\d*b)").find(stem)
        var sizePart = sizeMatch?.value ?: ""
        
        // Si no se encuentra "2b" pero hay un número solo al final (ej: gemma-4-2), lo usamos
        if (sizePart.isEmpty()) {
            val numMatch = Regex("-(\\d+)$").find(stem)
            if (numMatch != null) sizePart = numMatch.groupValues[1] + "b"
        }

        // Limpiar el prefijo 'e' si existe para el tag final (ej: e2b -> 2b)
        if (sizePart.startsWith("e")) {
            sizePart = sizePart.substring(1)
        }

        // 3. Detectar si es una versión Instruct/Chat
        val isInstruct = stem.contains("-it") || stem.contains("-instruct") || stem.contains("-chat")
        val suffix = if (isInstruct) "-it" else ""

        if (familyName.isNotEmpty() && sizePart.isNotEmpty()) {
            return "$familyName:$sizePart$suffix"
        }

        // Fallback: Limpiar sufijos técnicos comunes
        return stem.replace(Regex("[-_](gpu|cpu|int4|int8|fp16|fp32|q4|q8|q4_0|q4_k_m|instruct|chat|it|task|litert|gguf).*$"), "")
            .trim('-')
    }

    private fun detectFamily(name: String): ModelFamily = when {
        name.contains("gemma3") -> ModelFamily.GEMMA3
        name.contains("gemma2") || name.contains("gemma-2") -> ModelFamily.GEMMA2
        name.contains("gemma")  -> ModelFamily.GEMMA
        name.contains("llama")  -> ModelFamily.LLAMA
        name.contains("phi")    -> ModelFamily.PHI
        name.contains("mistral")-> ModelFamily.MISTRAL
        name.contains("qwen")   -> ModelFamily.QWEN
        else                    -> ModelFamily.UNKNOWN
    }

    private fun buildDetails(name: String, file: File, family: ModelFamily): ModelDetails {
        // Estimar tamaño de parámetros desde el nombre o el tamaño del archivo
        val paramSize = Regex("(\\d+\\.?\\d*[bB])").find(name)?.value?.uppercase()
            ?: when {
                file.length() < 1_500_000_000L  -> "1B"
                file.length() < 3_000_000_000L  -> "2B"
                file.length() < 6_000_000_000L  -> "4B"
                file.length() < 12_000_000_000L -> "7B"
                else                             -> "13B+"
            }

        val quant = when {
            name.contains("int4") || name.contains("q4") -> "INT4"
            name.contains("int8") || name.contains("q8") -> "INT8"
            name.contains("fp16")                        -> "FP16"
            file.extension.lowercase() == "bin"          -> "INT4"  // MediaPipe default
            else                                         -> "Q4_0"
        }

        // Determinar si la familia soporta herramientas (Ollama usa esto para habilitar UI de tools)
        val families = mutableListOf(family.familyName)
        if (family != ModelFamily.UNKNOWN && family != ModelFamily.GEMMA) {
            // Gemma 1 no soporta herramientas nativamente tan bien como Gemma 2/3 o Llama 3
            families.add("tools")
        }

        return ModelDetails(
            format = if (file.extension == "gguf") "gguf" else "litert",
            family = family.familyName,
            families = families,
            parameterSize = paramSize,
            quantizationLevel = quant
        )
    }

    private fun computeDigest(file: File): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024) // Leer solo 1MB para el hash (rápido)
            file.inputStream().use { input ->
                val read = input.read(buffer)
                if (read > 0) md.update(buffer, 0, read)
            }
            // Añadir tamaño y fecha al hash para asegurar unicidad
            md.update(file.length().toString().toByteArray())
            md.update(file.lastModified().toString().toByteArray())
            
            val digest = md.digest()
            "sha256:" + digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback determinista si falla la lectura
            val raw = "${file.name}:${file.length()}:${file.lastModified()}"
            "sha256:" + raw.hashCode().toUInt().toString(16).padEnd(64, '0')
        }
    }

    private fun buildDefaultModelfile(name: String, family: ModelFamily) = buildString {
        appendLine("FROM $name")
        appendLine("PARAMETER temperature 0.8")
        appendLine("PARAMETER top_k 40")
        appendLine("PARAMETER top_p 0.9")
        appendLine("PARAMETER num_predict 512")
        appendLine("PARAMETER repeat_penalty 1.1")
        appendLine()
        appendLine("SYSTEM You are a helpful AI assistant running locally on an Android device.")
        appendLine()
        appendLine("TEMPLATE \"\"\"${buildTemplate(family)}\"\"\"")
    }

    private fun buildDefaultParameters() = """
        temperature    0.8
        top_k          40
        top_p          0.9
        num_predict    512
        repeat_penalty 1.1
    """.trimIndent()

    private fun buildTemplate(family: ModelFamily): String = when (family) {
        ModelFamily.GEMMA, ModelFamily.GEMMA2, ModelFamily.GEMMA3 -> """
            <start_of_turn>user
            {{ if .System }}{{ .System }}

            {{ end }}{{ .Prompt }}<end_of_turn>
            <start_of_turn>model
            {{ .Response }}<end_of_turn>
        """.trimIndent()

        ModelFamily.LLAMA -> """
            <|begin_of_text|>{{ if .System }}<|start_header_id|>system<|end_header_id|>
            {{ .System }}<|eot_id|>{{ end }}<|start_header_id|>user<|end_header_id|>
            {{ .Prompt }}<|eot_id|><|start_header_id|>assistant<|end_header_id|>
            {{ .Response }}<|eot_id|>
        """.trimIndent()

        ModelFamily.PHI -> """
            <|system|>
            {{ if .System }}{{ .System }}{{ end }}<|end|>
            <|user|>
            {{ .Prompt }}<|end|>
            <|assistant|>
            {{ .Response }}<|end|>
        """.trimIndent()

        ModelFamily.MISTRAL -> """
            [INST] {{ if .System }}{{ .System }}

            {{ end }}{{ .Prompt }} [/INST]{{ .Response }}
        """.trimIndent()

        ModelFamily.QWEN -> """
            <|im_start|>system
            {{ if .System }}{{ .System }}{{ else }}You are a helpful assistant.<|im_end|>
            {{ end }}<|im_start|>user
            {{ .Prompt }}<|im_end|>
            <|im_start|>assistant
            {{ .Response }}<|im_end|>
        """.trimIndent()

        ModelFamily.UNKNOWN -> """
            ### System:
            {{ if .System }}{{ .System }}{{ end }}
            ### User:
            {{ .Prompt }}
            ### Assistant:
            {{ .Response }}
        """.trimIndent()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parser de Modelfile
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseModelfileParams(modelfile: String): String = modelfile.lines()
        .filter { it.trim().startsWith("PARAMETER", ignoreCase = true) }
        .joinToString("\n") { line ->
            line.trim().removePrefix("PARAMETER").removePrefix("parameter").trim()
        }

    private fun parseModelfileSystem(modelfile: String): String {
        val lines = modelfile.lines()
        val idx   = lines.indexOfFirst { it.trim().startsWith("SYSTEM", ignoreCase = true) }
        return if (idx >= 0) lines[idx].trim().removePrefix("SYSTEM").removePrefix("system").trim()
        else ""
    }

    private fun parseModelfileTemplate(modelfile: String): String {
        // Template puede ser multilínea delimitado por triple comillas
        val match = Regex(
            """TEMPLATE\s+"{3}(.*?)"{3}""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(modelfile)
        return match?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    private fun isoFormat(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant)

    private fun ModelEntry.toModelInfo() = ModelInfo(
        name = name,
        model = name,
        modifiedAt = isoFormat(modifiedAt),
        size = size,
        digest = digest,
        details = details
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Tipos de soporte internos
// ─────────────────────────────────────────────────────────────────────────────

/** Entrada interna del catálogo (nunca sale de esta clase) */
internal data class ModelEntry(
    val name: String,
    val filePath: String,
    val size: Long,
    val digest: String,
    val modifiedAt: Instant,
    val modelfile: String,
    val parameters: String,
    val template: String,
    val systemPrompt: String,
    val details: ModelDetails,
    /** true = creado vía /api/create o /api/copy, false = archivo físico */
    val isAlias: Boolean
)

/** Familias de modelos soportadas para templates y metadatos */
internal enum class ModelFamily(val familyName: String) {
    GEMMA("gemma"),
    GEMMA2("gemma2"),
    GEMMA3("gemma3"),
    LLAMA("llama"),
    PHI("phi3"),
    MISTRAL("mistral"),
    QWEN("qwen2"),
    UNKNOWN("unknown")
}