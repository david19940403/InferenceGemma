package hn.fredi.inferencelocal.api.engine

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import hn.fredi.inferencelocal.api.models.ModelOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionManager(
    private val scope: CoroutineScope,
    private val onInactivityTimeout: suspend () -> Unit
) {
    companion object {
        private const val TAG = "SessionManager"
        const val DEFAULT_SESSION_ID = "default"
    }

    private val sessionMutex = Mutex()
    private val conversations = object : LinkedHashMap<String, Conversation>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Conversation>?): Boolean {
            if (size > 10) {
                eldest?.value?.close()
                Log.d(TAG, "Sesión '${eldest?.key}' eliminada por límite de capacidad.")
                return true
            }
            return false
        }
    }

    var inactivityTimeoutMs: Long = 300_000L // 5 min
    private var lastActivityTime: Long = 0L
    private var inactivityJob: Job? = null

    var maxSessions: Int = 5

    fun getIdleTimeMs(): Long = if (lastActivityTime == 0L) 0L
    else System.currentTimeMillis() - lastActivityTime

    suspend fun getOrCreateConversation(
        engine: Engine,
        sessionId: String,
        options: ModelOptions?
    ): Conversation = sessionMutex.withLock {
        resetInactivityTimer()
        val existing = conversations[sessionId]
        if (existing != null) return existing

        val config = ConversationConfig(
            samplerConfig = SamplerConfig(
                temperature = (options?.temperature ?: 0.7f).toDouble(),
                topK = options?.topK ?: 40,
                topP = (options?.topP ?: 0.9f).toDouble()
            )
        )

        val newConv = engine.createConversation(config)
        conversations[sessionId] = newConv
        return newConv
    }

    suspend fun clearSession(sessionId: String = DEFAULT_SESSION_ID) = sessionMutex.withLock {
        conversations.remove(sessionId)?.close()
        Log.d(TAG, "Sesión '$sessionId' eliminada.")
    }

    fun listSessions(): Set<String> = conversations.keys.toSet()

    fun getActiveSessionsCount(): Int = conversations.size

    fun resetInactivityTimer() {
        lastActivityTime = System.currentTimeMillis()
        inactivityJob?.cancel()
        
        if (inactivityTimeoutMs <= 0) return

        inactivityJob = scope.launch {
            delay(inactivityTimeoutMs)
            val idleTime = System.currentTimeMillis() - lastActivityTime
            if (idleTime >= inactivityTimeoutMs) {
                Log.i(TAG, "Inactividad detectada (${idleTime/1000}s). Ejecutando callback...")
                onInactivityTimeout()
            }
        }
    }

    fun closeAll() {
        conversations.values.forEach { it.close() }
        conversations.clear()
        inactivityJob?.cancel()
    }
}
