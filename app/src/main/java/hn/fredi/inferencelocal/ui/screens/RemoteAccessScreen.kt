package hn.fredi.inferencelocal.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import hn.fredi.inferencelocal.remote.*

@Composable fun RemoteAccessScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by RemoteAccessService.state.collectAsState()
    val store = remember { RemoteSettingsStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val active = state.running || state.starting
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Acceso remoto", style = MaterialTheme.typography.headlineMedium)
        Text("Este dispositivo ejecuta el modelo. Conecta una PC u otro Android desde la misma red o una VPN.")
        Text(if(state.running) "● Servidor web activo" else if(state.starting) "Iniciando servidor…" else "Servidor detenido",color=MaterialTheme.colorScheme.primary)
        state.urls.forEach { url ->
            Card { Column(Modifier.padding(16.dp)) { Text(url,style=MaterialTheme.typography.titleMedium);TextButton(onClick={clipboard.setText(AnnotatedString(url))}) {Text("Copiar dirección")} } }
        }
        OutlinedTextField(port,{port=it},label={Text("Puerto HTTP")},enabled=!active,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(settings.token,{},label={Text("Clave de acceso")},readOnly=true,visualTransformation=if(reveal) VisualTransformation.None else PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        Row {
            TextButton(onClick={reveal=!reveal}) {Text(if(reveal) "Ocultar clave" else "Mostrar clave")}
            TextButton(onClick={clipboard.setText(AnnotatedString(settings.token))}) {Text("Copiar clave")}
        }
        OutlinedButton(enabled=!active,onClick={runCatching { settings=settings.copy(token=newRemoteToken());store.save(settings) }.onFailure {error=it.message}}) {Text("Generar nueva clave")}
        Button(enabled=!state.starting,onClick={
            runCatching {
                error=null
                if(state.running) context.stopService(Intent(context,RemoteAccessService::class.java))
                else {
                    settings=settings.copy(port=port.toIntOrNull() ?: error("Puerto inválido"));store.save(settings)
                    ContextCompat.startForegroundService(context,Intent(context,RemoteAccessService::class.java).setAction(RemoteAccessService.START))
                }
            }.onFailure {error=it.message}
        },modifier=Modifier.fillMaxWidth()) {Text(if(state.running) "Detener acceso remoto" else "Iniciar acceso remoto")}
        (error ?: state.error)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        HorizontalDivider()
        Text("Desde otro equipo",style=MaterialTheme.typography.titleLarge)
        Text("1. Abre la dirección en el navegador.\n2. Introduce la clave de acceso.\n3. Envía instrucciones o configura el engine.\n4. En otro Android puedes descargar el APK cliente desde esa página.")
        Text("El cliente no necesita modelos. La clave permite ejecutar las acciones configuradas y controlar el engine. HTTP está pensado para una red de confianza o VPN; el servicio no abre puertos en tu router.",style=MaterialTheme.typography.bodySmall)
    }
}
