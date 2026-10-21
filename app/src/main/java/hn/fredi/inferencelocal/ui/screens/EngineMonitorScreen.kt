package hn.fredi.inferencelocal.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import hn.fredi.inferencelocal.UiPhase
import hn.fredi.inferencelocal.UiState
import hn.fredi.inferencelocal.agent.*
import hn.fredi.inferencelocal.ui.components.*
import kotlinx.coroutines.*
import java.io.File

@Composable
fun EngineMonitorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime by EngineMonitor.state.collectAsState()
    val owner by EngineOwnership.owner.collectAsState()
    var saved by remember { mutableStateOf(runCatching { EngineSettingsStore(context).load() }.getOrDefault(EngineSettings())) }
    var model by rememberSaveable { mutableStateOf(saved.modelPath) }
    var backend by rememberSaveable { mutableStateOf(saved.backend) }
    var numCtx by rememberSaveable { mutableStateOf(saved.contextTokens.toString()) }
    var toolBudget by rememberSaveable { mutableStateOf(saved.toolBudgetTokens.toString()) }
    var temperature by rememberSaveable { mutableStateOf(saved.temperature.toString()) }
    var topK by rememberSaveable { mutableStateOf(saved.topK.toString()) }
    var topP by rememberSaveable { mutableStateOf(saved.topP.toString()) }
    var minRam by rememberSaveable { mutableStateOf(saved.minAvailableRamMb.toString()) }
    var message by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    var ram by remember { mutableStateOf("—") }
    var pss by remember { mutableStateOf("—") }
    val editable = !runtime.busy && !runtime.modelLoaded && !saving
    val otherEngine = owner != null && owner != "terminal"
    val unsaved = model.trim() != saved.modelPath || backend != saved.backend || numCtx.toIntOrNull() != saved.contextTokens ||
        temperature.toDoubleOrNull() != saved.temperature || topK.toIntOrNull() != saved.topK ||
        topP.toDoubleOrNull() != saved.topP || minRam.toIntOrNull() != saved.minAvailableRamMb || toolBudget.toIntOrNull() != saved.toolBudgetTokens
    val phase = when(runtime.phase) {
        EnginePhase.IDLE -> UiPhase.IDLE
        EnginePhase.LOADING -> UiPhase.LOADING_MODEL
        EnginePhase.READY, EnginePhase.INFERENCING -> UiPhase.RUNNING
        EnginePhase.RELEASING -> UiPhase.STOPPING
        EnginePhase.ERROR -> UiPhase.ERROR
    }
    LaunchedEffect(Unit) {
        while(isActive) {
            val values = withContext(Dispatchers.IO) {
                val info = ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                val jvm = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 / 1024 }
                "${jvm}M | Libre: ${info.availMem / 1024 / 1024}M" to "${memory.totalPss / 1024} MiB"
            }
            ram = values.first; pss = values.second
            delay(1500)
        }
    }
    fun command(action: String) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, LiteRTEngineService::class.java).setAction(action))
            message = ""
        } catch (e: Exception) { message = e.message ?: "No se pudo controlar el engine" }
    }
    Column(Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Monitor del engine", style = MaterialTheme.typography.headlineSmall)
        Text("Terminal en lenguaje natural · LiteRT-LM local", color = AccentCyan)
        Text("Catálogo: ${runtime.catalogToolCount} tools · ~${runtime.catalogTokensEstimate}/${runtime.catalogBudgetTokens} tokens estimados", style = MaterialTheme.typography.bodySmall, color = TextSecond)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when(runtime.phase) { EnginePhase.IDLE -> "DESCARGADO"; EnginePhase.LOADING -> "CARGANDO"; EnginePhase.READY -> "LISTO"; EnginePhase.INFERENCING -> "GENERANDO"; EnginePhase.RELEASING -> "LIBERANDO"; EnginePhase.ERROR -> "ERROR" }, color = if(runtime.error != null) AccentRed else AccentCyan)
                Text(runtime.message)
                Text((if(runtime.modelLoaded) runtime.model else File(saved.modelPath).name).ifBlank { "Sin modelo seleccionado" }, style = MaterialTheme.typography.bodySmall)
                Text(if(runtime.modelLoaded) "Backend activo: ${runtime.backend} · Contexto: ${runtime.contextTokens} tokens" else "Backend configurado: ${saved.backend} · El modelo se carga al ejecutar una intención", style = MaterialTheme.typography.bodySmall, color = TextSecond)
                if(otherEngine) Text("El servidor API tiene el engine reservado. Detén ese servidor para usar el modelo de la terminal.", color = AccentAmber)
                if(runtime.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { command(LiteRTEngineService.LOAD) }, enabled = !runtime.busy && !runtime.modelLoaded && !otherEngine && !saving && !unsaved && saved.modelPath.isNotBlank()) { Text("Cargar modelo") }
                    OutlinedButton(onClick = { command(LiteRTEngineService.UNLOAD) }, enabled = runtime.modelLoaded && !runtime.busy) { Text("Liberar modelo") }
                }
                if(runtime.busy) OutlinedButton(onClick = { command(LiteRTEngineService.CANCEL) }) { Text("Cancelar operación") }
                if(unsaved) Text("Tienes cambios sin guardar. Guarda la configuración antes de cargar el modelo.", color = AccentAmber, style = MaterialTheme.typography.bodySmall)
                runtime.error?.let { Text(it, color = AccentRed) }
            }
        }
        StatsCard(UiState(phase = phase, model = runtime.model, sessions = if(runtime.phase == EnginePhase.INFERENCING) 1 else 0,
            tokens = runtime.outputTokens, maxTokens = if(runtime.modelLoaded) runtime.contextTokens else saved.contextTokens,
            backend = runtime.backend, prefBackend = saved.backend, tps = runtime.tokensPerSecond, ttft = runtime.ttftMs, initTime = runtime.loadTimeMs, ram = ram))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoPill("RAM PROCESO (PSS)", pss, AccentAmber, Modifier.weight(1f))
            InfoPill("EVALUACIONES", runtime.evaluations.toString(), AccentCyan, Modifier.weight(1f))
        }
        Text("Rendimiento de la última inferencia · entrada: ${runtime.inputTokens} tokens. La memoria se actualiza mientras este monitor está visible.", style = MaterialTheme.typography.bodySmall, color = TextSecond)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Configuración del engine", style = MaterialTheme.typography.titleMedium)
                if(!editable) Text("Libera el modelo y espera a que termine la tarea para cambiar estos valores.", color = AccentAmber, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Ruta del modelo .litertlm") }, enabled = editable, singleLine = true)
                Box {
                    OutlinedButton(onClick = { modelsExpanded = true }, enabled = editable) { Text("Modelos disponibles") }
                    DropdownMenu(expanded = modelsExpanded, onDismissRequest = { modelsExpanded = false }) {
                        val files = context.getExternalFilesDir(null)?.listFiles()?.filter { it.extension == "litertlm" }.orEmpty()
                        if(files.isEmpty()) DropdownMenuItem(text = { Text("No hay modelos .litertlm instalados") }, onClick = {}, enabled = false)
                        files.forEach { file -> DropdownMenuItem(text = { Text(file.name) }, onClick = { model = file.path; modelsExpanded = false }) }
                    }
                }
                Text("Backend")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("NPU", "GPU", "CPU").forEach { choice -> FilterChip(selected = backend == choice, onClick = { backend = choice }, label = { Text(choice) }, enabled = editable) }
                }
                OutlinedTextField(numCtx, { numCtx = it }, Modifier.fillMaxWidth(), label = { Text("Contexto (512 a 4096 tokens)") }, enabled = editable, singleLine = true)
                OutlinedTextField(toolBudget, { toolBudget = it }, Modifier.fillMaxWidth(), label = { Text("Tools (256 a 2048 tokens estimados)") }, enabled = editable, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(temperature, { temperature = it }, Modifier.weight(1f), label = { Text("Temperatura") }, enabled = editable, singleLine = true)
                    OutlinedTextField(topK, { topK = it }, Modifier.weight(1f), label = { Text("Top K") }, enabled = editable, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(topP, { topP = it }, Modifier.weight(1f), label = { Text("Top P") }, enabled = editable, singleLine = true)
                    OutlinedTextField(minRam, { minRam = it }, Modifier.weight(1f), label = { Text("RAM mínima MiB") }, enabled = editable, singleLine = true)
                }
                Button(onClick = {
                    scope.launch {
                        saving = true
                        try {
                            val settings = EngineSettings(model.trim(), backend, numCtx.toIntOrNull() ?: error("Contexto inválido"), temperature.toDoubleOrNull() ?: error("Temperatura inválida"), topK.toIntOrNull() ?: error("Top K inválido"), topP.toDoubleOrNull() ?: error("Top P inválido"), minRam.toIntOrNull() ?: error("RAM mínima inválida")).copy(toolBudgetTokens = toolBudget.toIntOrNull() ?: error("Presupuesto de tools inválido"))
                            withContext(Dispatchers.IO) { EngineSettingsStore(context).save(settings) }
                            saved = settings; message = "Configuración guardada. Se aplicará en la próxima carga."
                        } catch(e: Exception) { message = e.message ?: "No se pudo guardar" } finally { saving = false }
                    }
                }, enabled = editable) { Text("Guardar configuración") }
                if(message.isNotBlank()) Text(message, color = AccentCyan)
                Text("La carga manual mantiene el modelo disponible hasta liberarlo. Las intenciones sin carga manual liberan el modelo al terminar. NPU requiere un modelo y un runtime compatibles; no se cambia a otro backend automáticamente.", style = MaterialTheme.typography.bodySmall, color = TextSecond)
            }
        }
    }
}
