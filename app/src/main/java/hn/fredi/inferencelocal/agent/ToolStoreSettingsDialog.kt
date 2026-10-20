package hn.fredi.inferencelocal.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun ToolStoreSettingsDialog(onDismiss: () -> Unit, onSaved: (ToolStoreSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ToolStoreSettingsStore(context) }
    val current = remember { store.load() }
    var url by remember { mutableStateOf(current.url) }
    var rewrite by remember { mutableStateOf(current.rewriteSearch) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val terminal by LiteRTEngineService.state.collectAsState()
    val runtime by EngineMonitor.state.collectAsState()
    val job by hn.fredi.inferencelocal.remote.RemoteJobs.state.collectAsState()
    val locked = terminal.busy || runtime.busy || job?.status == "queued"
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tool Store en la red") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Indica la dirección del PC que ejecuta el catálogo. Se usará la LAN o VPN activa, sin fijar una interfaz.")
            OutlinedTextField(url, { url=it;message="" }, label={Text("Dirección HTTP(S) y puerto")}, placeholder={Text("http://10.12.14.1:8000")}, singleLine=true, enabled=!locked && !testing,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled=!locked && !testing,onClick={url=ToolStoreEndpoint.preset(url,"10.12.14.1");message=""}) {Text("10.12.14.1")}
                OutlinedButton(enabled=!locked && !testing,onClick={url=ToolStoreEndpoint.preset(url,"192.168.9.1");message=""}) {Text("192.168.9.1")}
            }
            Text("Usa la IP del PC, sin /24. Conserva el puerto correcto; el habitual es 8000. El token sigue siendo el importado con la configuración SSH.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Switch(rewrite,{rewrite=it},enabled=!locked && !testing)
                Text("Reformular la búsqueda si no encuentra herramientas",Modifier.weight(1f))
            }
            Text("Hasta dos intentos con LiteRT. Las palabras alternativas solo se usan para buscar: no cambian la instrucción ni los argumentos de ejecución.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=!testing,onClick={
                scope.launch {
                    testing=true;message="Probando conexión…"
                    try {
                        val address=ToolStoreEndpoint.normalize(url)
                        withContext(Dispatchers.IO) { ToolStoreClient(ConfigVault(context).load().copy(tool_store_url=address)).probe() }
                        message="Conexión autorizada con el Tool Store"
                    } catch(e: CancellationException) {throw e}
                    catch(e: Exception) {message=e.message ?: "No se pudo conectar"}
                    finally {testing=false}
                }
            }) {Text(if(testing) "Probando…" else "Probar conexión")}
            if(message.isNotEmpty()) Text(message)
            if(locked) Text("Espera a que termine la operación para guardar.")
        }
    }, confirmButton = {
        TextButton(enabled=!locked && !testing,onClick={
            try {val value=ToolStoreSettings(ToolStoreEndpoint.normalize(url),rewrite);store.save(value);onSaved(value)}
            catch(e: Exception) {message=e.message ?: "No se pudo guardar"}
        }) {Text("Guardar")}
    }, dismissButton={TextButton(onClick=onDismiss){Text("Cerrar")}})
}
