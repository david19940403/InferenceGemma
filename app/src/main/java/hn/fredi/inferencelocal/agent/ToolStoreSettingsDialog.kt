package hn.fredi.inferencelocal.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.*

@Composable fun ToolStoreSettingsDialog(onDismiss: () -> Unit, onSaved: (ToolStoreSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ToolStoreSettingsStore(context) }
    val current = remember { store.load() }
    val changes by ToolStoreSettingsStore.changes.collectAsState()
    val saved = changes ?: current
    var editing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(current.url) }
    var primary by remember { mutableStateOf(current.primaryEnabled) }
    var nodes by remember { mutableStateOf(current.stores) }
    var tokens by remember { mutableStateOf(emptyMap<String,String>()) }
    fun draft() = ToolStoreSettings(url,rewriteSearch = current.rewriteSearch,primaryEnabled=primary,stores=nodes,tokens=tokens)
    var rewrite by remember { mutableStateOf(current.rewriteSearch) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val terminal by LiteRTEngineService.state.collectAsState()
    val runtime by EngineMonitor.state.collectAsState()
    val job by hn.fredi.inferencelocal.remote.RemoteJobs.state.collectAsState()
    val locked = terminal.busy || runtime.busy || job?.status == "queued"
    val pending = url.trim().trimEnd('/') != saved.url || rewrite != saved.rewriteSearch || primary != saved.primaryEnabled || nodes != saved.stores || tokens.isNotEmpty()
    LaunchedEffect(saved) { if(!editing) {url=saved.url;rewrite=saved.rewriteSearch;primary=saved.primaryEnabled;nodes=saved.stores} }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tool Store en la red") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Consulta hasta ocho catálogos en paralelo. Si una PC no responde, se usan las disponibles.")
            Row { Switch(primary,{primary=it;editing=true},enabled=!locked && !testing); Text("Activar catálogo principal") }
            Text("Dirección guardada: ${saved.url.ifBlank { "Sin dirección" }}",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(url, { url=it;editing=true;message="" }, label={Text("Dirección HTTP(S) y puerto")}, placeholder={Text("http://10.12.14.1:8000")}, singleLine=true, enabled=!locked && !testing,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled=!locked && !testing,onClick={url=ToolStoreEndpoint.preset(url,"10.12.14.1");editing=true;message=""}) {Text("10.12.14.1")}
                OutlinedButton(enabled=!locked && !testing,onClick={url=ToolStoreEndpoint.preset(url,"192.168.9.1");editing=true;message=""}) {Text("192.168.9.1")}
            }
            nodes.forEachIndexed { index, node ->
                fun update(value: ToolStoreNode) { nodes=nodes.toMutableList().also { it[index]=value };editing=true;message="" }
                HorizontalDivider()
                OutlinedTextField(node.label,{update(node.copy(label=it))},label={Text("Nombre de la PC")},enabled=!locked && !testing)
                OutlinedTextField(node.url,{update(node.copy(url=it))},label={Text("URL del catálogo")},enabled=!locked && !testing)
                Row { Switch(node.enabled,{update(node.copy(enabled=it))},enabled=!locked && !testing);Text("Activo") }
                Row { Switch(node.useImportedToken,{update(node.copy(useImportedToken=it))},enabled=!locked && !testing);Text("Usar token importado") }
                if(!node.useImportedToken) OutlinedTextField(tokens[node.id].orEmpty(),{tokens=tokens+(node.id to it);editing=true},
                    label={Text(if(node.hasToken) "Nuevo token (vacío conserva el actual)" else "Token propio")},
                    visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!locked && !testing)
                TextButton(enabled=!locked && !testing,onClick={nodes=nodes.filter {it.id!=node.id};tokens=tokens-node.id;editing=true}) {Text("Quitar catálogo")}
            }
            OutlinedButton(enabled=nodes.size<7 && !locked && !testing,onClick={nodes=nodes+ToolStoreNode("pc_"+java.util.UUID.randomUUID().toString().take(8),"Otra PC","");editing=true}) {Text("Agregar PC / catálogo")}
            Text("Usa la IP del PC, sin /24. Conserva el puerto correcto; el habitual es 8000. El principal usa el token importado; cada PC adicional puede usar uno propio.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Switch(rewrite,{rewrite=it;editing=true},enabled=!locked && !testing)
                Text("Reformular la búsqueda si no encuentra herramientas",Modifier.weight(1f))
            }
            Text("Hasta dos intentos con LiteRT. Las palabras alternativas solo se usan para buscar: no cambian la instrucción ni los argumentos de ejecución.",style=MaterialTheme.typography.bodySmall)
            if(pending) Text("Cambios sin guardar. La próxima intención seguirá usando la dirección guardada.",color=MaterialTheme.colorScheme.primary)
            OutlinedButton(enabled=!testing,onClick={
                scope.launch {
                    testing=true;message="Probando conexión…"
                    try {
                        val config=ConfigVault(context).load()
                        val client=FederatedToolStoreClient(store.connections(config,draft().copy(rewriteSearch=rewrite,tokens=tokens.filterValues {it.isNotBlank()}))) { source, query -> ToolStoreClient(config.copy(tool_store_url=source.url,token=source.token)).search(query) }
                        try { client.search("__connection_test__") } catch(e: IllegalStateException) { }
                        message=client.statuses.joinToString("\n") { "${it.label}: ${it.status} (${it.url})" }+"\nProbar no guarda cambios."
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
            try {val value=draft().copy(rewriteSearch=rewrite,tokens=tokens.filterValues {it.isNotBlank()});store.save(value);onSaved(value)}
            catch(e: Exception) {message=e.message ?: "No se pudo guardar"}
        }) {Text("Guardar")}
    }, dismissButton={TextButton(onClick=onDismiss){Text("Cerrar")}})
}
