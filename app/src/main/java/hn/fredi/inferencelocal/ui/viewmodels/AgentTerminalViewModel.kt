package hn.fredi.inferencelocal.ui.viewmodels

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hn.fredi.inferencelocal.getAvailableModels
import hn.fredi.inferencelocal.orchestrator.engine.LocalLlmGateway
import hn.fredi.inferencelocal.orchestrator.models.AgentEvent
import hn.fredi.inferencelocal.orchestrator.network.SSHConnectionPool
import hn.fredi.inferencelocal.orchestrator.resolver.ToolStoreClient
import hn.fredi.inferencelocal.orchestrator.worker.AgenticLoopWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File

data class TerminalUiState(
    val input: String = "",
    val toolStoreUrl: String = "http://192.168.1.10:8000",
    val busy: Boolean = false,
    val status: String = "Listo",
    val answer: String = "",
    val error: String? = null
)

class AgentTerminalViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(TerminalUiState())
        private set
    private var job: Job? = null
    private var pool: SSHConnectionPool? = null

    fun setInput(value: String) { state = state.copy(input = value) }
    fun setToolStoreUrl(value: String) { state = state.copy(toolStoreUrl = value) }

    fun execute() {
        if (state.busy || state.input.isBlank()) return
        val context = getApplication<Application>()
        val model = getAvailableModels(context).firstOrNull()
        if (model == null) {
            state = state.copy(error = "No hay un modelo LiteRT instalado. Configúralo en la pestaña Motor.")
            return
        }
        val sshDir = File(context.filesDir, "ssh")
        val key = File(sshDir, "id_ed25519")
        val knownHosts = File(sshDir, "known_hosts")
        if (!key.isFile || !knownHosts.isFile) {
            state = state.copy(error = "Faltan files/ssh/id_ed25519 o files/ssh/known_hosts en el almacenamiento privado de la app.")
            return
        }
        pool?.close()
        pool = SSHConnectionPool(key, knownHosts)
        val worker = AgenticLoopWorker(
            ToolStoreClient(state.toolStoreUrl),
            LocalLlmGateway(model.substringBeforeLast('.')),
            pool!!
        )
        state = state.copy(busy = true, status = "Resolviendo herramientas…", answer = "", error = null)
        job = viewModelScope.launch {
            worker.process(state.input)
                .catch { e -> state = state.copy(busy = false, status = "Error", error = e.message ?: e.javaClass.simpleName) }
                .collect { event ->
                    state = when (event) {
                        is AgentEvent.Resolving -> state.copy(status = "Buscando herramientas…")
                        is AgentEvent.Thinking -> state.copy(status = "Evaluando · ciclo ${event.iteration}/4")
                        is AgentEvent.Executing -> state.copy(status = "Ejecutando ${event.tool} en ${event.host}…")
                        is AgentEvent.Finished -> state.copy(busy = false, status = "Completado", answer = event.answer)
                    }
                }
        }
    }

    fun cancel() { job?.cancel(); state = state.copy(busy = false, status = "Cancelado") }

    override fun onCleared() { pool?.close(); super.onCleared() }
}
