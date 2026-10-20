package hn.fredi.inferencelocal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import hn.fredi.inferencelocal.ui.components.*
import hn.fredi.inferencelocal.ui.viewmodels.AgentTerminalViewModel

@Composable
fun AgentTerminalScreen(modifier: Modifier = Modifier, viewModel: AgentTerminalViewModel = viewModel()) {
    val state = viewModel.state
    Column(
        modifier = modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
            Text("INFRASTRUCTURE AGENT", color = AccentCyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("Terminal en lenguaje natural", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Cada ejecución es una transacción independiente y auditable.", color = TextMuted, fontSize = 13.sp)
        }
        Card(modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = state.toolStoreUrl, onValueChange = viewModel::setToolStoreUrl,
                    enabled = !state.busy, label = { Text("Tool Store") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.input, onValueChange = viewModel::setInput,
                    enabled = !state.busy, label = { Text("¿Qué quieres hacer?") },
                    placeholder = { Text("Ej. descarga este video en la Mac…") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = if (state.busy) viewModel::cancel else viewModel::execute,
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.busy) Color(0xFF8B3144) else AccentCyan)) {
                        Text(if (state.busy) "Cancelar" else "Ejecutar", color = BG, fontWeight = FontWeight.Bold)
                    }
                    if (state.busy) CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Text(state.status, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        if (state.answer.isNotBlank() || state.error != null) {
            Card(modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.error == null) "RESULTADO" else "ERROR",
                        color = if (state.error == null) AccentCyan else Color(0xFFFF6B7A), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(state.error ?: state.answer, color = TextPrimary, fontSize = 15.sp)
                }
            }
        }
        Text("Requiere el motor activo y credenciales SSH en el almacenamiento privado de la app.", color = TextMuted, fontSize = 11.sp)
    }
}
