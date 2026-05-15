package hn.fredi.inferencelocal.api.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import hn.fredi.inferencelocal.api.models.ChatMessage
import hn.fredi.inferencelocal.api.models.ModelOptions
import hn.fredi.inferencelocal.api.models.StreamToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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

    private var engine: Engine? = null
    // Mapa de conversaciones para soportar múltiples sesiones de chat con estado
    private val conversations = mutableMapOf<String, Conversation>()
    
    private var isInitialized = false
    private var currentCtx = 2048
    val maxTokens: Int get() = currentCtx

    // Parametros por defecto configurables
    var defaultTemperature: Float = 0.7f
    var defaultTopK: Int = 40
    var defaultTopP: Float = 0.9f

    // Métricas globales
    private val _totalTokensGenerated = AtomicLong(0L)
    val totalTokensGenerated: Long get() = _totalTokensGenerated.get()
    val activeSessions: Int get() = conversations.size

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida del motor
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun load(path: String, options: ModelOptions? = null) = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = java.io.File(path)
            if (!file.exists()) {
                throw Exception("El archivo del modelo no existe en la ruta: $path")
            }

            val requestedCtx = options?.numCtx ?: 2048
            if (isInitialized && path == modelPath && requestedCtx == currentCtx) {
                Log.d(TAG, "El modelo ya está cargado con el mismo contexto: $path")
                return@withContext
            }

            Log.i(TAG, "Iniciando carga de modelo: ${file.name} (Contexto: $requestedCtx)")
            
            // Validación de formato GGUF (Incompatible con LiteRT-LM)
            if (isGguf(file)) {
                throw Exception("El archivo ${file.name} está en formato GGUF. LiteRT-LM solo soporta modelos LiteRT (.tflite). Por favor descarga la versión 'LiteRT' desde Kaggle o HuggingFace.")
            }

            modelPath = path
            unloadInternal()

            val backends = listOf(
                "GPU" to { Backend.GPU() },
                "CPU" to { Backend.CPU() }
            )

            var lastError: Throwable? = null
            for ((name, factory) in backends) {
                try {
                    Log.d(TAG, "Intentando inicializar con backend $name...")
                    val backend = factory()
                    val config = EngineConfig(
                        modelPath = path,
                        backend = backend,
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = options?.numCtx ?: 2048,
                        maxNumImages = null,
                        cacheDir = context.cacheDir.path
                    )
                    
                    val newEngine = Engine(config)
                    newEngine.initialize()
                    engine = newEngine
                    isInitialized = true
                    currentCtx = requestedCtx
                    Log.i(TAG, "Motor LiteRT-LM cargado exitosamente usando $name ✓")
                    return@withContext
                } catch (e: Throwable) {
                    Log.w(TAG, "Error con backend $name: ${e.message}")
                    lastError = e
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

    suspend fun unload() = loadMutex.withLock {
        unloadInternal()
    }

    private fun unloadInternal() {
        try {
            conversations.values.forEach { it.close() }
            conversations.clear()
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar el motor: ${e.message}")
        } finally {
            engine = null
            modelPath = null // Reset modelPath too
            isInitialized = false
            _totalTokensGenerated.set(0L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gestión de Sesiones
    // ─────────────────────────────────────────────────────────────────────────

    private fun getOrCreateConversation(sessionId: String, options: ModelOptions?): Conversation {
        val eng = engine ?: throw IllegalStateException("Motor no inicializado. Llama a load() primero.")
        return conversations.getOrPut(sessionId) {
            val config = ConversationConfig(
                samplerConfig = SamplerConfig(
                    temperature = (options?.temperature ?: defaultTemperature).toDouble(),
                    topK = options?.topK ?: defaultTopK,
                    topP = (options?.topP ?: defaultTopP).toDouble()
                )
            )
            Log.d(TAG, "Creando sesión '$sessionId' con num_ctx=${options?.numCtx ?: currentCtx}")
            eng.createConversation(config)
        }
    }

    fun clearSession(sessionId: String = DEFAULT_SESSION_ID) {
        conversations.remove(sessionId)?.close()
        Log.d(TAG, "Sesión '$sessionId' eliminada.")
    }

    fun listSessions(): Set<String> = conversations.keys.toSet()

    // ─────────────────────────────────────────────────────────────────────────
    // Generación Síncrona
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(
        prompt: String,
        system: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val sid = "temp_gen_${System.currentTimeMillis()}"
        val formattedPrompt = buildGemmaPrompt(prompt, system ?: DEFAULT_SYSTEM_PROMPT)
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

    suspend fun chat(
        messages: List<ChatMessage>,
        sessionId: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val sid = sessionId ?: "temp_chat_${System.currentTimeMillis()}"
        val prompt = buildGemmaChatPrompt(messages)
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
    // Streaming con Delta real
    // ─────────────────────────────────────────────────────────────────────────

    fun chatStream(
        messages: List<ChatMessage>,
        sessionId: String? = null,
        options: ModelOptions? = null
    ): Flow<StreamToken> = callbackFlow {
        val sid = sessionId ?: "temp_stream_${System.currentTimeMillis()}"
        val prompt = buildGemmaChatPrompt(messages)
        logPrompt("stream", prompt)

        try {
            val conv = getOrCreateConversation(sid, options)
            var tokenCount = 0
            val maxTokens = options?.numPredict ?: Int.MAX_VALUE

            conv.sendMessageAsync(prompt).collect { result ->
                val delta = result.text
                if (delta.isNotEmpty() && tokenCount < maxTokens) {
                    tokenCount++
                    _totalTokensGenerated.incrementAndGet()
                    trySend(StreamToken(text = delta, isDone = false))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en stream: ${e.message}")
        } finally {
            trySend(StreamToken(text = "", isDone = true))
            if (sessionId == null) clearSession(sid)
            close()
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // Formateo de Prompts (Gemma 2 IT)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildGemmaPrompt(userText: String, systemText: String): String = buildString {
        append("<start_of_turn>user\n")
        append("$systemText\n\n${userText.trim()}")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    private fun buildGemmaChatPrompt(messages: List<ChatMessage>): String = buildString {
        val systemMsg = messages
            .firstOrNull { it.role.lowercase() == "system" }
            ?.content
            ?.trim()
            ?: DEFAULT_SYSTEM_PROMPT

        val chatTurns = messages.filter { it.role.lowercase() != "system" }

        chatTurns.forEachIndexed { index, msg ->
            val role = msg.role.lowercase()
            val gemmaRole = when (role) {
                "assistant", "model" -> "model"
                else -> "user"
            }

            append("<start_of_turn>$gemmaRole\n")
            if (index == 0 && gemmaRole == "user") {
                append("$systemMsg\n\n${msg.content.trim()}")
            } else {
                append(msg.content.trim())
            }
            append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }

    private fun cleanResponse(text: String): String = text
        .replace(Regex("<start_of_turn>(user|model)\n?"), "")
        .replace("<end_of_turn>", "")
        .replace(Regex("^(user|model)\n"), "")
        .trim()

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun embed(text: String): List<Float> = withContext(Dispatchers.IO) {
        val vector = FloatArray(384)
        text.forEachIndexed { i, char ->
            val idx = (char.code + i) % 384
            vector[idx] += 1.0f
        }
        val norm = sqrt(vector.map { it * it }.sum())
        if (norm > 0f) vector.map { it / norm } else vector.toList()
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun logPrompt(label: String, prompt: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val preview = if (prompt.length > LOG_PROMPT_PREVIEW)
                "${prompt.take(LOG_PROMPT_PREVIEW)}..." else prompt
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
