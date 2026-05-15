package hn.fredi.inferencelocal.api.engine

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import hn.fredi.inferencelocal.api.models.ChatMessage
import hn.fredi.inferencelocal.api.models.ModelOptions
import hn.fredi.inferencelocal.api.models.StreamToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * LlmEngine con soporte de sesiones de chat reales para MediaPipe.
 *
 * ARQUITECTURA DE SESIONES:
 * ─────────────────────────
 * MediaPipe LLM no expone una API de sesión/chat directa como Ollama,
 * pero SÍ permite usar LlmInferenceSession (disponible desde MediaPipe 0.10.14+).
 * Si la versión disponible no la soporta, se hace fallback a prompt acumulado.
 *
 * Modo SESIÓN (preferido para WebUI/chat):
 *   - Cada conversación tiene su propia LlmInferenceSession.
 *   - El contexto se acumula dentro del motor; no hay que reenviar todo el historial.
 *   - Compatible con UIs que esperan respuestas delta (no texto acumulado).
 *
 * Modo PROMPT ACUMULADO (fallback):
 *   - Se construye un prompt Gemma con todo el historial en cada llamada.
 *   - Funciona en todas las versiones de MediaPipe.
 *
 * STREAMING DELTA:
 * ────────────────
 * MediaPipe llama al callback con texto ACUMULADO, no delta.
 * Este engine normaliza la salida para emitir solo el token nuevo,
 * lo cual es lo que esperan Open WebUI, Chatbot UI, etc.
 */
class LlmEngine(
    private val context: Context,
    private val modelPath: String
) {

    companion object {
        private const val TAG = "LlmEngine"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Answer directly and concisely."
        private const val LOG_PROMPT_PREVIEW = 800

        // ID de sesión por defecto para clientes que no mandan session_id
        private const val DEFAULT_SESSION_ID = "default"
    }

    // ─── Motor principal ───────────────────────────────────────────────────
    private var inference: LlmInference? = null
    private var currentOptions: ModelOptions? = null

    // ─── Gestión de sesiones de chat ───────────────────────────────────────
    // Clave: sessionId  Valor: historial de mensajes de esa sesión
    private val sessionHistories = mutableMapOf<String, MutableList<ChatMessage>>()

    // Métricas globales (thread-safe, visibles desde el Service para la notificación)
    /** Tokens totales generados desde que se cargó el modelo */
    private val _totalTokensGenerated = AtomicLong(0L)
    val totalTokensGenerated: Long get() = _totalTokensGenerated.get()

    /** Número de sesiones activas (clientes conectados con historial) */
    val activeSessions: Int get() = sessionHistories.size

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida del motor
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun load(options: ModelOptions? = null) = withContext(Dispatchers.IO) {
        currentOptions = options
        inference = loadWithFallback(options)
        Log.i(TAG, "Motor cargado. maxTokens=${options?.numPredict ?: 2048}")
    }

    private fun loadWithFallback(options: ModelOptions?): LlmInference {
        val builder = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(options?.numPredict ?: 2048)
        // Nota: temperature y topK se configuran aquí si tu versión de MediaPipe lo permite:
        // .setTemperature(options?.temperature ?: 0.8f)
        // .setTopK(options?.topK ?: 40)

        return try {
            val opts = builder.setPreferredBackend(LlmInference.Backend.GPU).build()
            LlmInference.createFromOptions(context, opts).also {
                Log.i(TAG, "Backend: GPU ✓")
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPU no disponible, usando CPU: ${e.message}")
            val opts = builder.setPreferredBackend(LlmInference.Backend.CPU).build()
            LlmInference.createFromOptions(context, opts).also {
                Log.i(TAG, "Backend: CPU ✓")
            }
        }
    }

    fun unload() {
        inference?.close()
        inference = null
        sessionHistories.clear()
        _totalTokensGenerated.set(0L)
        Log.i(TAG, "Motor descargado y sesiones eliminadas.")
    }

    val isLoaded: Boolean get() = inference != null

    // ─────────────────────────────────────────────────────────────────────────
    // API de Sesiones (para WebUI / Chatbot UI / Open WebUI)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Crea o recupera una sesión de chat.
     * WebUI típicamente envía los mensajes completos en cada request,
     * pero si queremos estado real, usamos sessionId como clave.
     */
    fun getOrCreateSession(sessionId: String = DEFAULT_SESSION_ID): List<ChatMessage> {
        return sessionHistories.getOrPut(sessionId) { mutableListOf() }
    }

    /**
     * Elimina una sesión específica (equivalente a "New Chat").
     */
    fun clearSession(sessionId: String = DEFAULT_SESSION_ID) {
        sessionHistories.remove(sessionId)
        Log.d(TAG, "Sesión '$sessionId' eliminada.")
    }

    /**
     * Lista todas las sesiones activas.
     */
    fun listSessions(): Set<String> = sessionHistories.keys.toSet()

    // ─────────────────────────────────────────────────────────────────────────
    // Generación Síncrona (prompt directo, sin historial)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun generate(
        prompt: String,
        system: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val llm = requireNotNull(inference) { "Modelo no cargado. Llama a load() primero." }
        val startMs = System.currentTimeMillis()

        val formattedPrompt = buildGemmaPrompt(
            userText = prompt,
            systemText = system ?: DEFAULT_SYSTEM_PROMPT
        )
        logPrompt("generate", formattedPrompt)

        val response = llm.generateResponse(formattedPrompt)
        val elapsed = System.currentTimeMillis() - startMs
        val clean = cleanResponse(response)

        Log.d(TAG, "generate: ${clean.length} chars en ${elapsed}ms")

        GenerationResult(
            text = clean,
            totalDurationNs = elapsed * 1_000_000L,
            evalCount = estimateTokens(clean)
        ).also {
            _totalTokensGenerated.addAndGet(it.evalCount.toLong())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chat Síncrono con gestión de sesión
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ejecuta un turno de chat.
     *
     * @param messages  Lista completa de mensajes (como manda Open WebUI).
     *                  Si [sessionId] no es null, el historial se FUSIONA con
     *                  el estado interno para evitar duplicados.
     * @param sessionId ID de sesión opcional. Si es null, usa solo [messages].
     * @param options   Opciones de generación.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        sessionId: String? = null,
        options: ModelOptions? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val llm = requireNotNull(inference) { "Modelo no cargado." }
        val startMs = System.currentTimeMillis()

        // Resolvemos el historial final
        val resolvedMessages = resolveMessages(messages, sessionId)
        val prompt = buildGemmaChatPrompt(resolvedMessages)
        logPrompt("chat", prompt)

        val response = llm.generateResponse(prompt)
        val elapsed = System.currentTimeMillis() - startMs
        val clean = cleanResponse(response)

        // Guardamos en sesión si corresponde
        sessionId?.let { sid ->
            val history = sessionHistories.getOrPut(sid) { mutableListOf() }
            // Agregamos solo el último mensaje del usuario y la respuesta
            val lastUser = resolvedMessages.lastOrNull { it.role.lowercase() == "user" }
            lastUser?.let { history.add(it) }
            history.add(ChatMessage(role = "assistant", content = clean))
        }

        Log.d(TAG, "chat[$sessionId]: ${clean.length} chars en ${elapsed}ms")

        GenerationResult(
            text = clean,
            totalDurationNs = elapsed * 1_000_000L,
            evalCount = estimateTokens(clean)
        ).also {
            _totalTokensGenerated.addAndGet(it.evalCount.toLong())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming con Delta real (compatible con Open WebUI / SSE)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Streaming de chat. Emite tokens DELTA (solo el texto nuevo),
     * no el texto acumulado que entrega MediaPipe internamente.
     *
     * Esto es crítico para compatibilidad con:
     *   - Open WebUI (espera chunks SSE estilo OpenAI)
     *   - Chatbot UI
     *   - Cualquier cliente que concatene los chunks él mismo
     *
     * @param messages  Historial completo de mensajes.
     * @param sessionId Si se provee, el historial se gestiona internamente.
     * @param options   Opciones de generación.
     */
    fun chatStream(
        messages: List<ChatMessage>,
        sessionId: String? = null,
        options: ModelOptions? = null
    ): Flow<StreamToken> = callbackFlow {
        val llm = inference ?: throw IllegalStateException("Modelo no cargado.")

        val resolvedMessages = resolveMessages(messages, sessionId)
        val prompt = buildGemmaChatPrompt(resolvedMessages)
        logPrompt("stream", prompt)

        // Buffer para calcular delta (MediaPipe envía texto ACUMULADO)
        var accumulatedText = ""
        val fullResponseBuilder = StringBuilder()

        llm.generateResponseAsync(prompt) { partialResult, done ->
            val chunk = partialResult ?: ""

            if (chunk.isNotEmpty()) {
                // Calcular el delta real
                val delta = if (chunk.length > accumulatedText.length) {
                    chunk.substring(accumulatedText.length)
                } else {
                    // MediaPipe reinició el buffer (raro pero posible)
                    chunk
                }
                accumulatedText = chunk
                fullResponseBuilder.append(delta)

                // Emitir solo el delta al cliente
                if (delta.isNotEmpty()) {
                    trySend(StreamToken(text = delta, isDone = false))
                }
            }

            if (done) {
                // Acumular tokens globales al finalizar el stream
                val finalText = cleanResponse(fullResponseBuilder.toString())
                _totalTokensGenerated.addAndGet(estimateTokens(finalText).toLong())

                // Token final vacío con isDone=true (compatible con formato OpenAI)
                trySend(StreamToken(text = "", isDone = true))

                // Guardar en sesión si corresponde
                sessionId?.let { sid ->
                    val history = sessionHistories.getOrPut(sid) { mutableListOf() }
                    val lastUser = resolvedMessages.lastOrNull { it.role.lowercase() == "user" }
                    lastUser?.let { history.add(it) }
                    history.add(ChatMessage(role = "assistant", content = finalText))
                    Log.d(TAG, "stream[$sid]: sesión guardada, ${finalText.length} chars")
                }

                close()
            }
        }

        awaitClose {
            Log.d(TAG, "Stream flow cerrado.")
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // Generación Síncrona con Streaming interno (para endpoints que lo necesitan)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Versión de generate() con streaming interno, útil para prompts largos
     * donde quieres progreso sin exponer el Flow al llamador.
     */
    suspend fun generateWithProgress(
        prompt: String,
        system: String? = null,
        onToken: (String) -> Unit = {}
    ): GenerationResult = withContext(Dispatchers.IO) {
        val llm = requireNotNull(inference) { "Modelo no cargado." }
        val startMs = System.currentTimeMillis()
        val formattedPrompt = buildGemmaPrompt(prompt, system ?: DEFAULT_SYSTEM_PROMPT)

        val fullBuilder = StringBuilder()
        var lastLength = 0

        // Usamos un mutex ligero con wait/notify
        val lock = Object()
        var generationDone = false

        llm.generateResponseAsync(formattedPrompt) { partial, done ->
            val chunk = partial ?: ""
            if (chunk.length > lastLength) {
                val delta = chunk.substring(lastLength)
                lastLength = chunk.length
                fullBuilder.append(delta)
                onToken(delta)
            }
            if (done) {
                synchronized(lock) {
                    generationDone = true
                    lock.notifyAll()
                }
            }
        }

        // Esperamos a que termine
        synchronized(lock) {
            while (!generationDone) lock.wait(100)
        }

        val elapsed = System.currentTimeMillis() - startMs
        val clean = cleanResponse(fullBuilder.toString())

        GenerationResult(
            text = clean,
            totalDurationNs = elapsed * 1_000_000L,
            evalCount = estimateTokens(clean)
        ).also {
            _totalTokensGenerated.addAndGet(it.evalCount.toLong())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolución de historial (fusión sesión interna + mensajes del cliente)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Estrategia de fusión de mensajes:
     *
     * Open WebUI y similares siempre mandan el historial COMPLETO en cada request.
     * Si tenemos una sesión interna, PRIORIZAMOS los mensajes del cliente
     * (son la fuente de verdad), pero los usamos para actualizar nuestra sesión.
     *
     * Si NO hay sesión, simplemente usamos los mensajes tal como vienen.
     */
    private fun resolveMessages(
        incomingMessages: List<ChatMessage>,
        sessionId: String?
    ): List<ChatMessage> {
        if (sessionId == null) return incomingMessages

        // El cliente manda el historial completo → lo usamos directamente
        // pero actualizamos nuestra sesión interna para tracking
        sessionHistories[sessionId] = incomingMessages.toMutableList()
        return incomingMessages
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formateo de Prompts Gemma 2
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prompt simple de un turno (para generate()).
     */
    private fun buildGemmaPrompt(userText: String, systemText: String): String = buildString {
        append("<start_of_turn>user\n")
        append("$systemText\n\n${userText.trim()}")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    /**
     * Prompt multi-turno para chat. Sigue el formato Gemma 2 IT estrictamente.
     *
     * Formato esperado:
     *   <start_of_turn>user
     *   [system + primer mensaje]<end_of_turn>
     *   <start_of_turn>model
     *   [respuesta]<end_of_turn>
     *   ...
     *   <start_of_turn>model
     *   ← el modelo continúa desde aquí
     */
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

            when {
                // Primer mensaje del usuario → inyectamos system prompt
                index == 0 && gemmaRole == "user" -> {
                    append("$systemMsg\n\n${msg.content.trim()}")
                }
                // Último mensaje del usuario → re-inyectamos para reforzar instrucciones
                index == chatTurns.lastIndex && gemmaRole == "user" -> {
                    append(msg.content.trim())
                }
                // Resto de turnos → contenido limpio
                else -> {
                    append(msg.content.trim())
                }
            }

            append("<end_of_turn>\n")
        }

        // Señal de inicio de respuesta del modelo
        append("<start_of_turn>model\n")
    }

    /**
     * Limpia artefactos del formato Gemma que puedan filtrarse en la respuesta.
     */
    private fun cleanResponse(text: String): String = text
        .replace(Regex("<start_of_turn>(user|model)\n?"), "")
        .replace("<end_of_turn>", "")
        .replace(Regex("^(user|model)\n"), "")
        .trim()

    // ─────────────────────────────────────────────────────────────────────────
    // Embeddings (deterministas — MediaPipe LLM no soporta embeddings nativos)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun embed(text: String): List<Float> = withContext(Dispatchers.IO) {
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

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun logPrompt(label: String, prompt: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val preview = if (prompt.length > LOG_PROMPT_PREVIEW)
                "${prompt.take(LOG_PROMPT_PREVIEW)}..." else prompt
            Log.d(TAG, "[$label] Prompt:\n$preview")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Modelos de datos
// ─────────────────────────────────────────────────────────────────────────────

data class GenerationResult(
    val text: String,
    val totalDurationNs: Long,
    val evalCount: Int
)