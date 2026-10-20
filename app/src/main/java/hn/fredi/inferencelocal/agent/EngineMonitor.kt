package hn.fredi.inferencelocal.agent

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EngineSettings(
    val modelPath: String = "",
    val backend: String = "GPU",
    val contextTokens: Int = 1984,
    val temperature: Double = 0.0,
    val topK: Int = 1,
    val topP: Double = 1.0,
    val minAvailableRamMb: Int = 512
) {
    fun validate() {
        require(modelPath.isNotBlank() && modelPath.endsWith(".litertlm")) { "Selecciona un modelo .litertlm" }
        require(backend in listOf("NPU", "GPU", "CPU")) { "Backend inválido" }
        require(contextTokens in 512..1984) { "El contexto de la terminal debe estar entre 512 y 1984 tokens" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "Temperatura: 0 a 2" }
        require(topK in 1..64) { "Top K: 1 a 64" }
        require(topP.isFinite() && topP > 0.0 && topP <= 1.0) { "Top P debe ser mayor que 0 y hasta 1" }
        require(minAvailableRamMb in 256..8192) { "RAM mínima: 256 a 8192 MiB" }
    }
}

class EngineSettingsStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "engine-settings.json"))
    companion object { private val lock = Any() }
    fun load(): EngineSettings = synchronized(lock) {
        if (file.baseFile.exists()) return@synchronized Json.decodeFromString<EngineSettings>(file.readFully().toString(Charsets.UTF_8)).also { it.validate() }
        val config = runCatching { ConfigVault(context).load() }.getOrNull()
        EngineSettings(
            modelPath = config?.model_path ?: context.getExternalFilesDir(null)?.listFiles()?.firstOrNull { it.extension == "litertlm" }?.path.orEmpty(),
            backend = config?.backend ?: "GPU"
        )
    }
    fun save(settings: EngineSettings) = synchronized(lock) {
        check(!EngineMonitor.state.value.busy && !EngineMonitor.state.value.modelLoaded) { "Libera el modelo antes de cambiar su configuración" }
        settings.validate()
        val output = file.startWrite()
        try { output.write(Json.encodeToString(settings).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
}

enum class EnginePhase { IDLE, LOADING, READY, INFERENCING, RELEASING, ERROR }
data class EngineMonitorState(
    val phase: EnginePhase = EnginePhase.IDLE,
    val modelLoaded: Boolean = false,
    val taskActive: Boolean = false,
    val model: String = "",
    val backend: String = "—",
    val contextTokens: Int = 1984,
    val message: String = "Modelo descargado",
    val error: String? = null,
    val loadTimeMs: Long = 0,
    val ttftMs: Long = 0,
    val tokensPerSecond: Double = 0.0,
    val inputTokens: Int = 0,
    val outputTokens: Long = 0,
    val evaluations: Int = 0
) {
    val busy: Boolean get() = taskActive || phase in listOf(EnginePhase.LOADING, EnginePhase.INFERENCING, EnginePhase.RELEASING)
}
object EngineMonitor {
    private val mutableState = MutableStateFlow(EngineMonitorState())
    val state = mutableState.asStateFlow()
    fun update(block: (EngineMonitorState) -> EngineMonitorState) { mutableState.update(block) }
}

/** A single native model owns device accelerators at a time across both services. */
object EngineOwnership {
    private val mutableOwner = MutableStateFlow<String?>(null)
    val owner = mutableOwner.asStateFlow()
    @Synchronized fun claim(name: String) { check(mutableOwner.value == null || mutableOwner.value == name) { "Detén o libera el engine de ${mutableOwner.value} antes de cargar otro modelo" }; mutableOwner.value = name }
    @Synchronized fun release(name: String) { if (mutableOwner.value == name) mutableOwner.value = null }
}
