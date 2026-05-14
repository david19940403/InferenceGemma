package hn.kwikstop.inferencelocal.api.engine

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import hn.kwikstop.inferencelocal.api.models.ChatMessage
import hn.kwikstop.inferencelocal.api.models.ModelOptions
import hn.kwikstop.inferencelocal.api.models.StreamToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * LlmEngine optimizado para emulación de API de Google (Gemma/Gemma 2).
 */
class LlmEngine(
    private val context: Context,
    private val modelPath: String
) {

    companion object {
        private const val TAG = "LlmEngine"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Answer directly. Do not use conversational fillers."
        private const val LOG_PROMPT_PREVIEW = 600
    }

    private var inference: LlmInference? = null
    // Guardamos las opciones actuales para detectar si hay que reinicializar el motor
    private var currentOptions: ModelOptions? = null

    suspend fun load(options: ModelOptions? = null) = withContext(Dispatchers.IO) {
        currentOptions = options
        inference = loadWithFallback(options)
    }

    private fun loadWithFallback(options: ModelOptions?): LlmInference {
        val builder = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            // Configuramos parámetros que MediaPipe permite en el setup
            .setMaxTokens(options?.numPredict ?: 2048)
            //.setTemperature(options?.temperature ?: 0.7f)
            //.setTopK(options?.topK ?: 40)

        return try {
            val opts = builder.setPreferredBackend(LlmInference.Backend.GPU).build()
            LlmInference.createFromOptions(context, opts).also { Log.i(TAG, "Backend: GPU ✓") }
        } catch (e: Exception) {
            Log.w(TAG, "GPU Fallback a CPU...")
            val opts = builder.setPreferredBackend(LlmInference.Backend.CPU).build()
            LlmInference.createFromOptions(context, opts)
        }
    }

    fun unload() {
        inference?.close()
        inference = null
    }

    val isLoaded: Boolean get() = inference != null

    // ─────────────────────────────────────────────────────────────────────────
    // Generación Síncrona
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(
        prompt: String,
        system: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val llm = requireNotNull(inference) { "Modelo no cargado" }

        val startMs = System.currentTimeMillis()
        val formattedPrompt = buildGemmaPrompt(prompt, system ?: DEFAULT_SYSTEM_PROMPT)

        logPrompt("generate", formattedPrompt)

        val response = llm.generateResponse(formattedPrompt)
        val elapsed = System.currentTimeMillis() - startMs

        GenerationResult(
            text = response.trim(),
            totalDurationNs = elapsed * 1_000_000L,
            evalCount = estimateTokens(response)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chat & Streaming (Corregido para emulación API)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun chat(
        messages: List<ChatMessage>,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val llm = requireNotNull(inference) { "Modelo no cargado" }
        val startMs = System.currentTimeMillis()

        val prompt = buildGemmaChatPrompt(messages)
        logPrompt("chat", prompt)

        val response = llm.generateResponse(prompt)
        val elapsed = System.currentTimeMillis() - startMs

        GenerationResult(
            text = cleanResponse(response),
            totalDurationNs = elapsed * 1_000_000L,
            evalCount = estimateTokens(response)
        )
    }

    fun chatStream(
        messages: List<ChatMessage>,
        options: ModelOptions? = null
    ): Flow<StreamToken> = callbackFlow {
        val llm = inference ?: throw IllegalStateException("Modelo no cargado")

        val prompt = buildGemmaChatPrompt(messages)
        logPrompt("stream", prompt)

        llm.generateResponseAsync(prompt) { partialResult, done ->
            // MediaPipe a veces envía el texto acumulado o nulo
            val textChunk = partialResult ?: ""
            if (textChunk.isNotEmpty() || done) {
                trySend(StreamToken(text = textChunk, isDone = done))
            }
            if (done) close()
        }

        awaitClose { /* Cleanup si es necesario */ }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // Formateo de Prompts (Gemma 2 Standard)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildGemmaPrompt(userText: String, systemText: String): String = buildString {
        append("<start_of_turn>user\n")
        append("$systemText\n\n$userText")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    private fun buildGemmaChatPrompt(messages: List<ChatMessage>): String = buildString {
        val systemMsg = messages.find { it.role.lowercase() == "system" }?.content ?: DEFAULT_SYSTEM_PROMPT

        // Filtramos para procesar solo user y assistant
        val chatHistory = messages.filter { it.role.lowercase() != "system" }

        chatHistory.forEachIndexed { index, msg ->
            val role = msg.role.lowercase()
            val gemmaRole = if (role == "assistant" || role == "model") "model" else "user"

            append("<start_of_turn>$gemmaRole\n")

            // Si es el ÚLTIMO mensaje del usuario, le re-inyectamos el sistema
            // para que no olvide las instrucciones de formato/estilo.
            if (index == chatHistory.lastIndex && gemmaRole == "user") {
                append("Instructions: $systemMsg\n\nInput: ${msg.content.trim()}")
            } else if (index == 0 && gemmaRole == "user") {
                // Si es el primer mensaje, también lo ponemos por si acaso
                append("$systemMsg\n\n${msg.content.trim()}")
            } else {
                append(msg.content.trim())
            }
            append("<end_of_turn>\n")
        }

        // Señal de inicio del modelo
        append("<start_of_turn>model\n")

        // --- EL TRUCO DEL PRE-FILL ---
        // Si detectamos que es una tarea de sugerencias, forzamos el inicio
        if (systemMsg.contains("Suggest", ignoreCase = true)) {
            append("Here are 3-5 follow-up questions:\n1.")
            // Esto obliga al modelo a seguir la lista y no saludar
        }
    }

    private fun cleanResponse(text: String): String {
        return text.replace("<start_of_turn>", "")
            .replace("<end_of_turn>", "")
            .replace("model\n", "")
            // Eliminamos el pre-fill si lo agregamos manualmente arriba
            .replace("Here are 3-5 follow-up questions:\n", "")
            .trim()
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun logPrompt(label: String, prompt: String) {
        val preview = if (prompt.length > LOG_PROMPT_PREVIEW)
            prompt.take(LOG_PROMPT_PREVIEW) + "..." else prompt
        Log.d(TAG, "[$label] Prompt: $preview")
    }

    suspend fun embed(text: String): List<Float> = withContext(Dispatchers.IO) {
        // Mantenemos tu lógica determinista ya que MediaPipe LLM no soporta Embeddings nativos aún
        generateDeterministicEmbedding(text, 384)
    }

    private fun generateDeterministicEmbedding(text: String, dimensions: Int): List<Float> {
        val vector = FloatArray(dimensions)
        text.forEachIndexed { i, char ->
            val idx = (char.code + i) % dimensions
            vector[idx] += 1.0f
        }
        val norm = sqrt(vector.map { it * it }.sum())
        return if (norm > 0f) vector.map { it / norm } else vector.toList()
    }
}

data class GenerationResult(
    val text: String,
    val totalDurationNs: Long,
    val evalCount: Int
)