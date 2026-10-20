package hn.fredi.inferencelocal.agent

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import hn.fredi.inferencelocal.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InfrastructureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Surface { TerminalScreen() } } }
    }
}

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by LiteRTEngineService.state.collectAsState()
    val runtime by EngineMonitor.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var configStatus by remember { mutableStateOf(runCatching { ConfigVault(context).load().let { "${it.hosts.size} hosts · ${EngineSettingsStore(context).load().backend}" } }.getOrDefault("Sin configuración")) }
    var importing by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            configStatus = try {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(128 * 1024).toString(Charsets.UTF_8) } ?: error("No se pudo abrir el archivo")
                    ConfigVault(context).apply { save(text) }.load().let { "${it.hosts.size} hosts · ${it.backend} · configuración cifrada" }
                }
            } catch (e: Exception) { e.message ?: "Configuración inválida" }
            importing = false
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Terminal SSH", style = MaterialTheme.typography.headlineSmall)
        Text("Una intención. Una transacción.", color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(configStatus)
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !state.busy && !runtime.busy && !runtime.modelLoaded && !importing) { Text(if (importing) "Importando…" else "Importar configuración JSON") }
                Text("LiteRT-LM local · Go · SSH con claves de host verificadas", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("¿Qué quieres hacer?") }, placeholder = { Text("Consulta el estado de la MacBook Fedora") }, minLines = 3, enabled = !state.busy)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                try { ContextCompat.startForegroundService(context, Intent(context, LiteRTEngineService::class.java).setAction(LiteRTEngineService.RUN).putExtra("query", query)) }
                catch (e: Exception) { configStatus = e.message ?: "No se pudo iniciar el servicio" }
            }, enabled = query.isNotBlank() && query.toByteArray().size <= 512 && !state.busy && !importing) { Text("Ejecutar intención") }
            if (state.busy) OutlinedButton(onClick = { context.startService(Intent(context, LiteRTEngineService::class.java).setAction(LiteRTEngineService.CANCEL)) }) { Text("Cancelar") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.message, style = MaterialTheme.typography.titleMedium)
        if (state.result.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Text(state.result, Modifier.padding(18.dp)) }
        Text("Hasta 3 herramientas recuperadas y 4 evaluaciones por intención. Los scripts se ejecutan en los hosts configurados.", style = MaterialTheme.typography.bodySmall)
    }
}
