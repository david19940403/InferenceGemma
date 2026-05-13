package hn.kwikstop.inferencelocal.api.engine


import android.content.Context
import android.util.Log
import hn.kwikstop.inferencelocal.api.models.ChatMessage
import hn.kwikstop.inferencelocal.api.models.ModelOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * LlmEngine
 *
 * Capa de abstracción sobre MediaPipe LlmInference.
 * Expone métodos de alto nivel para generación de texto, chat y embeddings.
 */
class LlmEngine(
    private val context: Context,
    private val modelPath: String
) {

    companion object {
        private const val TAG = "LlmEngine"
    }

    private var inference: LlmInference? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Carga el modelo en la GPU. Puede tardar varios segundos la primera vez.
     * Debe llamarse desde una corutina (Dispatchers.IO).
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Cargando modelo desde: $modelPath")
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setPreferredBackend(LlmInference.Backend.GPU)
            .setMaxTokens(1024)
            .build()

        inference = LlmInference.createFromOptions(context, options)
        Log.i(TAG, "Modelo cargado correctamente en GPU")
    }

    /** Libera la instancia del modelo. */
    fun unload() {
        inference?.close()
        inference = null
        Log.i(TAG, "Modelo descargado")
    }

    val isLoaded: Boolean get() = inference != null

    // ─────────────────────────────────────────────────────────────────────────
    // Generación de texto  (para /api/generate)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Genera una respuesta dado un prompt de texto plano.
     * Aplica el template de Gemma-IT automáticamente.
     *
     * @param prompt   Texto del usuario.
     * @param system   Prompt de sistema opcional.
     * @param options  Parámetros de inferencia opcionales.
     * @return         Texto generado.
     */
    suspend fun generate(
        prompt: String,
        system: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        requireNotNull(inference) { "El modelo no está cargado. Llama a load() primero." }

        val startMs = System.currentTimeMillis()

        val formattedPrompt = buildGemmaPrompt(
            userText   = prompt,
            systemText = system
        )

        val response = inference!!.generateResponse(formattedPrompt)
        val elapsed  = System.currentTimeMillis() - startMs

        // Estimación simple de tokens (≈ palabras * 1.3)
        val evalCount = estimateTokens(response)

        GenerationResult(
            text          = response.trim(),
            totalDurationNs = elapsed * 1_000_000L,
            evalCount     = evalCount
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chat  (para /api/chat y /v1/chat/completions)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Genera una respuesta dado un historial de mensajes con roles.
     *
     * @param messages  Lista ordenada de mensajes (system, user, assistant…).
     * @param options   Parámetros de inferencia opcionales.
     * @return          Texto del asistente.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        requireNotNull(inference) { "El modelo no está cargado." }

        val startMs = System.currentTimeMillis()
        val prompt  = buildGemmaChatPrompt(messages)
        val response = inference!!.generateResponse(prompt)
        val elapsed  = System.currentTimeMillis() - startMs

        GenerationResult(
            text          = response.trim(),
            totalDurationNs = elapsed * 1_000_000L,
            evalCount     = estimateTokens(response)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Embeddings  (para /api/embeddings y /v1/embeddings)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Genera un vector de embedding para el texto dado.
     *
     * NOTA: MediaPipe LLM Inference SDK no expone embeddings directamente.
     * Como aproximación determinista, generamos un vector de 384 dimensiones
     * basado en los valores hash de los tokens del texto, normalizado a norma 1.
     *
     * Para embeddings de producción se recomienda usar un modelo dedicado
     * (ej. all-MiniLM-L6-v2 vía TFLite) o la API de embeddings de Google.
     *
     * @param text  Texto a representar.
     * @return      Vector de floats de 384 dimensiones, norma ≈ 1.
     */
    suspend fun embed(text: String): List<Float> = withContext(Dispatchers.IO) {
        generateDeterministicEmbedding(text, dimensions = 384)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers de formato de prompt  (Gemma Instruction Tuned Template)
    // https://ai.google.dev/gemma/docs/formatting
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildGemmaPrompt(userText: String, systemText: String?): String = buildString {
        systemText?.let {
            // Gemma no tiene token [SYSTEM] oficial; lo insertamos como primer turno usuario
            append("<start_of_turn>user\n")
            append("[System]: ${it.trim()}")
            append("\n<end_of_turn>\n")
            append("<start_of_turn>model\n")
            append("Understood. I will follow these instructions.")
            append("\n<end_of_turn>\n")
        }
        append("<start_of_turn>user\n")
        append(userText.trim())
        append("\n<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    private fun buildGemmaChatPrompt(messages: List<ChatMessage>): String = buildString {
        for (msg in messages) {
            when (msg.role.lowercase()) {
                "system" -> {
                    append("<start_of_turn>user\n")
                    append("[System]: ${msg.content.trim()}")
                    append("\n<end_of_turn>\n")
                    append("<start_of_turn>model\n")
                    append("Understood.")
                    append("\n<end_of_turn>\n")
                }
                "user" -> {
                    append("<start_of_turn>user\n")
                    append(msg.content.trim())
                    append("\n<end_of_turn>\n")
                }
                "assistant", "model" -> {
                    append("<start_of_turn>model\n")
                    append(msg.content.trim())
                    append("\n<end_of_turn>\n")
                }
            }
        }
        append("<start_of_turn>model\n")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    /** Estimación de tokens por aproximación (1 token ≈ 0.75 palabras). */
    private fun estimateTokens(text: String): Int =
        (text.split(Regex("\\s+")).size * 1.33).toInt().coerceAtLeast(1)

    /**
     * Genera un embedding determinista de [dimensions] dimensiones
     * usando una combinación de hashes de n-gramas de caracteres.
     * La semilla es el contenido del texto, por lo que el mismo texto
     * produce siempre el mismo vector.
     */
    private fun generateDeterministicEmbedding(text: String, dimensions: Int): List<Float> {
        val vector = FloatArray(dimensions)
        val chars  = text.toCharArray()

        // Acumular señal de trigramas de caracteres
        for (i in chars.indices) {
            val c1 = chars[i].code
            val c2 = if (i + 1 < chars.size) chars[i + 1].code else 0
            val c3 = if (i + 2 < chars.size) chars[i + 2].code else 0

            val hash = (c1 * 31 + c2) * 31 + c3
            val idx  = ((hash % dimensions) + dimensions) % dimensions

            vector[idx] += 1.0f
            // Distribución suave a vecinos
            vector[(idx + 1) % dimensions] += 0.5f
            vector[(idx - 1 + dimensions) % dimensions] += 0.5f
        }

        // Normalizar a norma L2 ≈ 1
        val norm = sqrt(vector.map { it * it }.sum())
        return if (norm > 0f) vector.map { it / norm } else vector.toList()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Resultado de una generación
// ─────────────────────────────────────────────────────────────────────────────

data class GenerationResult(
    val text: String,
    val totalDurationNs: Long,
    val evalCount: Int
)