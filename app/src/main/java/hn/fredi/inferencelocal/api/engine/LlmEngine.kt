package hn.fredi.inferencelocal.api.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import hn.fredi.inferencelocal.api.models.ChatMessage
import hn.fredi.inferencelocal.api.models.ModelOptions
import hn.fredi.inferencelocal.api.models.StreamToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * LlmEngine migrado a LiteRT-LM (com.google.ai.edge.litertlm).
 * 
 * Esta implementación reemplaza MediaPipe GenAI por el nuevo motor LiteRT-LM,
 * manteniendo compatibilidad con la API de sesiones y streaming delta.
 */
class LlmEngine(
    private val context: Context
) {

    private var modelPath: String? = null
    private val loadMutex = Mutex()

    companion object {
        private const val TAG = "LlmEngine"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Answer directly and concisely."
        private const val LOG_PROMPT_PREVIEW = 800

        // ID de sesión por defecto
        private const val DEFAULT_SESSION_ID = "default"

        init {
            try {
                // LiteRT-LM requiere cargar la librería nativa LiteRt
                System.loadLibrary("LiteRt")
                Log.i(TAG, "Loaded libLiteRt.so")
            } catch (e: Throwable) {
                Log.w(TAG, "Native library load warning: ${e.message}")
            }
        }
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engine: Engine? = null
    private val sessionManager = SessionManager(engineScope) {
        unload()
    }

    // Proporciones dinámicas configurables
    var maxSessions: Int 
        get() = sessionManager.maxSessions
        set(value) { sessionManager.maxSessions = value }

    var inactivityTimeoutMs: Long
        get() = sessionManager.inactivityTimeoutMs
        set(value) { sessionManager.inactivityTimeoutMs = value }

    private fun getAvailableRam(): Long {
        val mi = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(mi)
        return mi.availMem
    }
    
    private var isInitialized = false
    private var currentCtx = 4096
    val maxTokens: Int get() = currentCtx
    private var activeBackendName: String = "Unknown"
    var preferredBackend: String = "GPU" // Cambiado por defecto a GPU

    // Parametros por defecto configurables
    var defaultTemperature: Float = 0.7f
    var defaultTopK: Int = 40
    var defaultTopP: Float = 0.9f
    var defaultNumCtx: Int = 4096
    
    // Control de memoria personalizable (MB)
    var minAvailableRamMb: Long = 1536 

    private var nativeRuntimeConfigured = false

    // Métricas globales
    private val _totalTokensGenerated = AtomicLong(0L)
    val totalTokensGenerated: Long get() = _totalTokensGenerated.get()
    val activeSessions: Int get() = sessionManager.getActiveSessionsCount()

    // Métricas de rendimiento
    var lastInitTimeMs: Long = 0
    var lastTimeToFirstTokenMs: Long = 0
    var lastTokensPerSecond: Double = 0.0

    fun getActiveBackend(): String = activeBackendName

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida del motor
    // ─────────────────────────────────────────────────────────────────────────
    private fun isMultimodalModel(path: String): Boolean {
        // Los modelos multimodales de LiteRT tienen "multimodal" o "vision" en el nombre
        // o tienen extensión .litertlm con soporte de imagen
        val name = java.io.File(path).name.lowercase()
        return name.contains("vision") ||
                name.contains("multimodal") ||
                name.contains("4b") ||      // Gemma 4 es multimodal
                name.contains("gemma3n") || // Gemma 3n es multimodal
                name.contains("gemma-4") ||
                name.contains("llava") ||
                name.contains("paligemma") ||
                name.contains("moondream") ||
                name.contains("pixtral")
    }
    suspend fun load(path: String, options: ModelOptions? = null) = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = java.io.File(path)
            if (!file.exists()) {
                throw Exception("El archivo del modelo no existe en la ruta: $path")
            }

            val requestedCtx = options?.numCtx ?: defaultNumCtx
            val availableRam = getAvailableRam()
            val ramThreshold = minAvailableRamMb * 1024L * 1024L
            val lowRam = availableRam < ramThreshold

            if (isInitialized && path == modelPath && requestedCtx == currentCtx && !lowRam) {
                Log.d(TAG, "El modelo ya está cargado con el mismo contexto: $path")
                return@withContext
            }

            Log.i(TAG, "Iniciando carga de modelo: ${file.name} (RAM Libre: ${availableRam / 1024 / 1024}MB)")
            
            // Validación de formato GGUF (Incompatible con LiteRT-LM)
            if (isGguf(file)) {
                throw Exception("El archivo ${file.name} está en formato GGUF. LiteRT-LM solo soporta modelos LiteRT (.tflite). Por favor descarga la versión 'LiteRT' desde Kaggle o HuggingFace.")
            }

            // Descarga forzada y limpieza si es un modelo nuevo o memoria baja
            if (isInitialized || lowRam) {
                Log.d(TAG, "Limpiando motor previo y forzando GC...")
                unloadInternal()
                System.gc()
                System.runFinalization()
                if (lowRam) delay(1000) // Pausa de seguridad para que el SO recupere memoria nativa
            }

            modelPath = path

            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            val isPixel = android.os.Build.MANUFACTURER.contains("Google", ignoreCase = true)
            val isSamsung = android.os.Build.MANUFACTURER.contains("Samsung", ignoreCase = true)

            // Estrategia de carga en cascada
            val backends = mutableListOf<Pair<String, () -> Backend>>()
            
            val pref = preferredBackend.uppercase()
            
            // 1. Agregar el preferido primero
            when (pref) {
                "NPU" -> backends.add("NPU" to {
                    configureNativeRuntime(nativeLibraryDir)
                    Backend.NPU(nativeLibraryDir = nativeLibraryDir)
                })
                "GPU" -> backends.add("GPU" to { Backend.GPU() })
                "CPU" -> backends.add("CPU" to { Backend.CPU() })
            }

            // 2. Agregar el resto como fallback si es "Auto" o para asegurar carga
            if (pref == "AUTO" || pref == "NPU") {
                if ((isSamsung || isPixel) && pref != "NPU") {
                    backends.add("NPU" to {
                        configureNativeRuntime(nativeLibraryDir)
                        Backend.NPU(nativeLibraryDir = nativeLibraryDir)
                    })
                }
                if (pref != "GPU") backends.add("GPU" to { Backend.GPU() })
                if (pref != "CPU") backends.add("CPU" to { Backend.CPU() })
            } else {
                // Si eligió GPU o CPU explícitamente, igual ponemos los otros al final por si acaso
                if (pref != "GPU") backends.add("GPU" to { Backend.GPU() })
                if (pref != "CPU") backends.add("CPU" to { Backend.CPU() })
            }

            var lastError: Throwable? = null
            val startTime = System.currentTimeMillis()
            
            for ((name, factory) in backends) {
                try {
                    Log.d(TAG, "Intentando inicializar con backend $name...")
                    
                    // IMPORTANTE: Resetear variables antes de cada intento
                    engine?.close()
                    engine = null
                    
                    val backend = try {
                        factory()
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallo al crear factory para $name: ${e.message}")
                        continue
                    }
                    val visionBackend: Backend? = if (isMultimodalModel(path)) {
                        Backend.GPU()
                    } else {
                        null // Modelo texto-only, no inicializar visión
                    }

                    val config = EngineConfig(
                        modelPath = path,
                        backend = backend,
                        maxNumTokens = requestedCtx,
                        maxNumImages = if (visionBackend != null) 1 else 0,
                        cacheDir = context.cacheDir.path,
                        visionBackend = visionBackend
                    )
                    
                    val newEngine = Engine(config)
                    newEngine.initialize()
                    
                    // Verificación de "salud" del modelo post-inicialización
                    engine = newEngine
                    isInitialized = true
                    currentCtx = requestedCtx
                    activeBackendName = name
                    lastInitTimeMs = System.currentTimeMillis() - startTime
                    
                    Log.i(TAG, "¡Éxito! Motor LiteRT-LM cargado con $name en ${lastInitTimeMs}ms ✓")
                    return@withContext
                } catch (e: Throwable) {
                    Log.e(TAG, "Error crítico con backend $name: ${e.message}")
                    lastError = e
                    // Forzar recolección de basura nativa antes del siguiente intento
                    System.gc()
                    System.runFinalization()
                    delay(500)
                }
            }
            
            val finalMsg = "Error al crear el motor para ${file.name}: ${lastError?.message}. " +
                          "Asegúrate de que no sea un archivo corrupto o un bundle .task incompatible."
            throw Exception(finalMsg)
        }
    }

    private fun isGguf(file: java.io.File): Boolean {
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic)
                String(magic) == "GGUF"
            }
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    private fun configureNativeRuntime(nativeLibraryDir: String) {
        if (nativeRuntimeConfigured) return
        try {
            android.system.Os.setenv("LD_LIBRARY_PATH", nativeLibraryDir, true)
            android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeLibraryDir, true)
            Log.i(TAG, "Set native library paths to $nativeLibraryDir")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set native library paths: ${e.message}")
        }
        nativeRuntimeConfigured = true
    }

    suspend fun unload() = loadMutex.withLock {
        unloadInternal()
    }

    private fun unloadInternal() {
        try {
            Log.d(TAG, "Descargando motor y sesiones...")
            sessionManager.closeAll()
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar el motor: ${e.message}")
        } finally {
            engine = null
            modelPath = null
            isInitialized = false
            _totalTokensGenerated.set(0L)
            System.gc()
            System.runFinalization()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gestión de Sesiones
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun getOrCreateConversation(sessionId: String, options: ModelOptions?): Conversation {
        val eng = engine ?: throw IllegalStateException("Motor no inicializado. Llama a load() primero.")
        return sessionManager.getOrCreateConversation(eng, sessionId, options)
    }

    suspend fun clearSession(sessionId: String = DEFAULT_SESSION_ID) = sessionManager.clearSession(sessionId)

    fun listSessions(): Set<String> = sessionManager.listSessions()

    suspend fun chat(
        messages: List<ChatMessage>,
        sessionId: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        sessionManager.resetInactivityTimer()
        val sid = sessionId ?: "temp_chat_${System.currentTimeMillis()}"
        val prompt = PromptFormatter.buildGemmaChatPrompt(messages) { base64 ->
            decodeImageSafely(base64)
        }
        checkContextLimit(prompt, options?.numCtx)
        logPrompt("chat", prompt)

        var finalResponse = ""
        try {
            val conv = getOrCreateConversation(sid, options)
            conv.sendMessageAsync(prompt).collect { result ->
                Log.v("LlmEngine", "Delta: ${result.text}")
                finalResponse += result.text
            }
        } finally {
            if (sessionId == null) clearSession(sid)
        }

        val clean = cleanResponse(finalResponse)
        GenerationResult(
            text = clean,
            totalDurationNs = 0,
            evalCount = estimateTokens(clean)
        ).also {
            _totalTokensGenerated.addAndGet(it.evalCount.toLong())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generación Síncrona
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(
        prompt: String,
        system: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        sessionManager.resetInactivityTimer()
        val sid = "temp_gen_${System.currentTimeMillis()}"
        val formattedPrompt = PromptFormatter.buildGemmaPrompt(system ?: DEFAULT_SYSTEM_PROMPT, prompt)
        val contents = Contents.of(Content.Text(formattedPrompt))
        checkContextLimit(contents, options?.numCtx)
        logPrompt("generate", formattedPrompt)

        var finalResponse = ""
        try {
            val conv = getOrCreateConversation(sid, options)
            conv.sendMessageAsync(formattedPrompt).collect { result ->
                Log.v("LlmEngine", "Delta: ${result.text}")
                finalResponse += result.text
            }
        } finally {
            clearSession(sid)
        }

        val clean = cleanResponse(finalResponse)
        GenerationResult(
            text = clean,
            totalDurationNs = 0,
            evalCount = estimateTokens(clean)
        ).also {
            _totalTokensGenerated.addAndGet(it.evalCount.toLong())
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Streaming con Delta real
    // ─────────────────────────────────────────────────────────────────────────

    fun chatStream(
        messages: List<ChatMessage>,
        sessionId: String? = null,
        options: ModelOptions? = null
    ): Flow<StreamToken> = callbackFlow {
        sessionManager.resetInactivityTimer()
        val sid = sessionId ?: "temp_stream_${System.currentTimeMillis()}"
        val prompt = PromptFormatter.buildGemmaChatPrompt(messages) { base64 ->
            decodeImageSafely(base64)
        }

        try {
            val regex = """\[IMAGEGENERATION\]\([^)]+\)""".toRegex()
            val isImage = regex.find(messages.lastOrNull()?.content ?: "")?.value
            if (isImage != null) {
                isImage.replace("IMAGEGENERATION","IMAGERESPONSE")
                trySend(StreamToken(text = "!$isImage", isDone = true))
            }else{ checkContextLimit(prompt, options?.numCtx)
            logPrompt("stream", prompt)
            val conv = getOrCreateConversation(sid, options)
            var tokenCount = 0
            val maxTokens = options?.numPredict ?: Int.MAX_VALUE
            
            val startTime = System.currentTimeMillis()
            var firstTokenTime = 0L

            // Evaluamos los mensajes empezando desde el último (más reciente)



                conv.sendMessageAsync(prompt).collect { result ->
                    if (firstTokenTime == 0L) {
                        firstTokenTime = System.currentTimeMillis()
                        lastTimeToFirstTokenMs = firstTokenTime - startTime
                    }

                    val delta = result.text
                    if (delta.isNotEmpty() && tokenCount < maxTokens) {
                        tokenCount++
                        _totalTokensGenerated.incrementAndGet()
                        trySend(StreamToken(text = delta, isDone = false))
                    }
                }

            // Encuentra la primera coincidencia en el texto

            
            val endTime = System.currentTimeMillis()
            val durationSec = (endTime - firstTokenTime) / 1000.0
            if (durationSec > 0.001 && tokenCount > 0) {
                val calculatedTps = tokenCount / durationSec
                lastTokensPerSecond = if (calculatedTps.isFinite()) calculatedTps else 0.0
            }
        }
        } catch (e: TokenLimitExceededException) {
            trySend(StreamToken(
                text = "\n\n⚠️ **Límite de tokens alcanzado**\n" +
                        "${e.message}\n\n" +
                        "Sugerencia: Puedes limpiar el chat o pedirme que 'comprima' la conversación para continuar.",
                isDone = true
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error en stream: ${e.message}")
            trySend(StreamToken(text = "\n[Error: ${e.message}]", isDone = true))
        } finally {
            trySend(StreamToken(text = "", isDone = true))
            if (sessionId == null) clearSession(sid)
            close()
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    private fun cleanResponse(text: String): String = text
        .replace(Regex("<start_of_turn>(user|model)\n?"), "")
        .replace("<end_of_turn>", "")
        .replace(Regex("^(user|model)\n"), "")
        .trim()

    fun decodeImageSafely(base64: String): Content.ImageBytes? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) {
                Log.w(TAG, "Base64 decodificó array vacío")
                return null
            }

            // Primero verificar dimensiones sin cargar el bitmap completo
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            // Escalar si la imagen es muy grande (>1024px) para evitar OOM
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, 1024, 1024)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Menos memoria que ARGB_8888
            }

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            if (bitmap == null) {
                Log.e(TAG, "BitmapFactory retornó null — formato no soportado")
                return null
            }

            val imageBytes = ByteArrayOutputStream().use { stream ->
                val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                bitmap.recycle() // Liberar memoria nativa inmediatamente
                if (!compressed) {
                    Log.e(TAG, "bitmap.compress falló")
                    return null
                }
                stream.toByteArray()
            }

            if (imageBytes.isEmpty()) {
                Log.e(TAG, "Compresión produjo array vacío")
                return null
            }

            Content.ImageBytes(imageBytes)

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM al decodificar imagen — imagen demasiado grande")
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Error inesperado al decodificar imagen: ${e.message}", e)
            null
        }
    }

    private fun calculateSampleSize(
        width: Int, height: Int,
        maxWidth: Int, maxHeight: Int
    ): Int {
        var sampleSize = 1
        if (width > maxWidth || height > maxHeight) {
            val widthRatio = width / maxWidth
            val heightRatio = height / maxHeight
            sampleSize = maxOf(widthRatio, heightRatio).coerceAtLeast(1)
        }
        return sampleSize
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    // Función pública para consultar el estado:
    fun getIdleTimeMs(): Long = sessionManager.getIdleTimeMs()

    fun isIdle(): Boolean = !isInitialized

    suspend fun embed(text: String): List<Float> = withContext(Dispatchers.IO) {
        val vector = FloatArray(384)
        text.forEachIndexed { i, char ->
            val idx = (char.code + i) % 384
            vector[idx] += 1.0f
        }
        val norm = sqrt(vector.map { it * it }.sum())
        if (norm > 0f) vector.map { it / norm } else vector.toList()
    }

    fun estimateTokens(text: String): Int = (text.length / 3).coerceAtLeast(1) // Más realista para español/código

    /**
     * Verifica si el prompt cabe en el contexto actual dejando margen para la respuesta.
     */
    private fun checkContextLimit(prompt: Any, requestedLimit: Int? = null) {
        val text = when (prompt) {
            is Contents -> prompt.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            is String -> prompt
            else -> prompt.toString()
        }
        val estimated = estimateTokens(text)
        val limit = requestedLimit ?: currentCtx

        // Dejamos un margen del 20% para la respuesta o al menos 256 tokens
        val margin = (limit * 0.2).toInt().coerceAtLeast(256)
        val actualLimit = (limit - margin).coerceAtLeast(margin)

        if (estimated > actualLimit) {
            throw TokenLimitExceededException(estimated, limit)
        }
    }

    /**
     * Comprime la conversación eliminando mensajes antiguos hasta que quepa en un ratio del contexto.
     * Mantiene el mensaje de sistema si existe.
     */
    fun compressConversation(messages: List<ChatMessage>, ratio: Float = 0.5f): List<ChatMessage> {
        val systemMsg = messages.find { it.role.lowercase() == "system" }
        val others = messages.filter { it.role.lowercase() != "system" }

        val targetTokens = (currentCtx * ratio).toInt()
        val currentList = others.toMutableList()

        while (currentList.isNotEmpty()) {
            val testMessages = if (systemMsg != null) listOf(systemMsg) + currentList else currentList
            val prompt = PromptFormatter.buildGemmaChatPrompt(testMessages) { base64 ->
                decodeImageSafely(base64)
            }
            val text = prompt.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            if (estimateTokens(text) <= targetTokens) break

            // Eliminar de dos en dos (par user-model) para coherencia
            if (currentList.size >= 2) {
                currentList.removeAt(0)
                currentList.removeAt(0)
            } else {
                currentList.removeAt(0)
            }
        }

        return if (systemMsg != null) listOf(systemMsg) + currentList else currentList
    }

    private fun logPrompt(label: String, prompt: Any) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val text = when(prompt) {
                is Contents -> prompt.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                is Content.Text -> prompt.text
                else -> prompt.toString()
            }
            val preview = if (text.length > LOG_PROMPT_PREVIEW)
                "${text.take(LOG_PROMPT_PREVIEW)}..." else text
            Log.d(TAG, "[$label] Prompt:\n$preview")
        }
    }
}

/**
 * Propiedad de extensión para extraer el texto puro de un mensaje de LiteRT-LM.
 * Filtra los contenidos para obtener solo las partes de texto y unirlas.
 */
private val Message.text: String
    get() = contents.contents.joinToString("") { content ->
        when (content) {
            is Content.Text -> content.text
            else -> "" // Ignorar otros tipos como imágenes por ahora
        }
    }

data class GenerationResult(
    val text: String,
    val totalDurationNs: Long,
    val evalCount: Int
)

/**
 * Excepción lanzada cuando el prompt excede el límite de tokens del contexto.
 */
class TokenLimitExceededException(val estimatedTokens: Int, val limit: Int) :
    Exception("Historial demasiado largo (~$estimatedTokens tokens, límite $limit).")
