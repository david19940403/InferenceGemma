package hn.fredi.inferencelocal.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

class RemoteBusy(message: String) : IllegalStateException(message)
@Serializable
data class RemoteJob(val id: String, val request_id: String, val action: String, val query: String,
    val status: String = "queued", val message: String = "Esperando al engine", val result: String = "")

/** One current transaction, shared by all clients. Repeating its request ID never executes twice. */
object RemoteJobs {
    private val mutableState = MutableStateFlow<RemoteJob?>(null)
    val state = mutableState.asStateFlow()
    val pending: Boolean get() = mutableState.value?.status == "queued"
    @Synchronized fun submit(action: String, query: String, requestId: String, busy: Boolean, start: (String) -> Unit): RemoteJob {
        require(requestId.matches(Regex("[a-zA-Z0-9_-]{8,80}"))) { "request_id inválido" }
        require(action in listOf("intent", "load", "unload"))
        if(action == "intent") require(query.isNotBlank() && query.toByteArray().size <= 512) { "La instrucción debe ocupar entre 1 y 512 bytes" }
        val previous = mutableState.value
        if(previous?.request_id == requestId) {
            require(previous.action == action && previous.query == query) { "request_id ya usado con otros parámetros" }
            return previous
        }
        if(busy || previous?.status in listOf("queued", "running")) throw RemoteBusy("Hay una operación en curso. Espera a que termine o cancélala.")
        val job = RemoteJob(UUID.randomUUID().toString(), requestId, action, query)
        mutableState.value = job
        try { start(job.id) } catch(e: Exception) { mutableState.value = job.copy(status = "failed", message = e.message ?: "No se pudo iniciar"); throw e }
        return mutableState.value!!
    }
    @Synchronized fun started(id: String?) { if(id != null && mutableState.value?.id == id) mutableState.value = mutableState.value!!.copy(status = "running", message = "Preparando operación") }
    @Synchronized fun progress(id: String?, message: String) { if(id != null && mutableState.value?.id == id) mutableState.value = mutableState.value!!.copy(message = message) }
    @Synchronized fun finish(id: String?, status: String, message: String, result: String = "") { if(id != null && mutableState.value?.id == id) mutableState.value = mutableState.value!!.copy(status = status, message = message, result = result) }
}
