package hn.kwikstop.inferencelocal
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import hn.kwikstop.inferencelocal.service.LlmServerService
import java.net.InetAddress
import java.net.NetworkInterface

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private lateinit var statusReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LlmServerTheme {
                LlmServerScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pantalla principal
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LlmServerScreen() {
    val context = LocalContext.current

    var modelSelected by remember { mutableStateOf("gemma-2b-it-gpu-int4.bin") }
    var isRunning  by remember { mutableStateOf(false) }
    var isLoading  by remember { mutableStateOf(false) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }
    val localIp    = remember { getLocalIpAddress() }

    // ── Escuchar broadcasts del servicio ─────────────────────────────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val running = intent.getBooleanExtra(LlmServerService.EXTRA_RUNNING, false)
                val error   = intent.getStringExtra(LlmServerService.EXTRA_ERROR)
                isRunning  = running
                isLoading  = false
                errorMsg   = error
            }
        }
        val filter = IntentFilter(LlmServerService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0A0F),
                        Color(0xFF0D1117),
                        Color(0xFF0A0A0F)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        ) {

            // ── Título ───────────────────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "LOCAL LLM",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    color = Color(0xFF4ADE80).copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Gemma 2B",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "Server",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Light,
                    color = Color(0xFF4ADE80),
                    letterSpacing = (-1).sp
                )
            }

            // ── Indicador de estado animado ──────────────────────────────────
            StatusIndicator(isRunning = isRunning, isLoading = isLoading)

            // ── Card de información ──────────────────────────────────────────
            ServerInfoCard(
                ip        = localIp ?: "No disponible",
                port      = LlmServerService.SERVER_PORT,
                isRunning = isRunning
            )

            ModelSelector {
                modelSelected = it
            }

            // ── Botón principal ──────────────────────────────────────────────
            ServerToggleButton(
                isRunning = isRunning,
                isLoading = isLoading,
                onClick   = {
                    if (isRunning) {
                        // Detener servicio
                        context.startService(
                            Intent(context, LlmServerService::class.java)
                                .apply { action = LlmServerService.ACTION_STOP }
                        )
                        isLoading = true
                    } else {
                        // Iniciar servicio
                        val serviceIntent = Intent(context, LlmServerService::class.java)
                            .apply {
                                action = LlmServerService.ACTION_START
                                putExtra(LlmServerService.EXTRA_MODEL_NAME, modelSelected) // Pasamos el nombre
                            }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        isLoading = true
                        errorMsg  = null
                    }
                }
            )

            // ── Mensaje de error ─────────────────────────────────────────────
            errorMsg?.let { msg ->
                Surface(
                    shape  = RoundedCornerShape(12.dp),
                    color  = Color(0xFFFF4444).copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text     = "⚠ $msg",
                        color    = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ── Instrucción de uso ───────────────────────────────────────────
            if (isRunning) {
                Text(
                    text = "Envía prompts desde cualquier dispositivo\nen la misma red Wi-Fi",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Componentes
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusIndicator(isRunning: Boolean, isLoading: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(contentAlignment = Alignment.Center) {
        if (isRunning || isLoading) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(
                        if (isLoading) Color(0xFFFBBF24).copy(alpha = 0.15f)
                        else Color(0xFF4ADE80).copy(alpha = 0.15f)
                    )
            )
        }
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isLoading -> Color(0xFFFBBF24).copy(alpha = 0.3f)
                        isRunning -> Color(0xFF4ADE80).copy(alpha = 0.3f)
                        else -> Color.White.copy(alpha = 0.08f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isLoading -> Color(0xFFFBBF24)
                            isRunning -> Color(0xFF4ADE80)
                            else -> Color.White.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}

@Composable
private fun ServerInfoCard(ip: String, port: Int, isRunning: Boolean) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = Color.White.copy(alpha = 0.04f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {

            InfoRow(label = "ESTADO") {
                Text(
                    text  = if (isRunning) "En línea" else "Apagado",
                    color = if (isRunning) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.4f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
            Spacer(Modifier.height(14.dp))

            InfoRow(label = "IP LOCAL") {
                Text(
                    text  = ip,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
            Spacer(Modifier.height(14.dp))

            InfoRow(label = "ENDPOINT") {
                Text(
                    text  = "POST :$port/api/generate",
                    color = Color(0xFF60A5FA),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp
                )
            }

            if (isRunning) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Spacer(Modifier.height(14.dp))

                Text(
                    text = """curl -X POST http://$ip:$port/api/generate \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Hola"}'""",
                    color      = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 10.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text     = label,
            color    = Color.White.copy(alpha = 0.3f),
            fontSize = 10.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily    = FontFamily.Monospace
        )
        content()
    }
}

fun getAvailableModels(context: Context): List<String> {
    val dir = context.getExternalFilesDir(null)
    return dir?.listFiles { file ->
        // Filtra por extensiones comunes de LiteRT/MediaPipe
        file.extension == "bin" || file.extension == "litert" || file.extension == "tflite" || file.extension == "litertlm"
    }?.map { it.name } ?: emptyList()
}

@Composable
fun ModelSelector(onModelSelected: (String) -> Unit) {
    val context = LocalContext.current
    val models = remember { getAvailableModels(context) }
    var expanded by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(models.firstOrNull() ?: "No hay modelos") }

    Column {
        Text("Seleccionar Modelo:")
        Box {
            Button(onClick = { expanded = true }) {
                Text(selectedModel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { modelName ->
                    DropdownMenuItem(
                        text = { Text(modelName) },
                        onClick = {
                            selectedModel = modelName
                            expanded = false
                            onModelSelected(modelName) // Guardamos la elección
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerToggleButton(
    isRunning: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick  = onClick,
        enabled  = !isLoading,
        shape    = RoundedCornerShape(16.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) Color(0xFFEF4444) else Color(0xFF4ADE80),
            contentColor   = if (isRunning) Color.White else Color(0xFF0A0A0F),
            disabledContainerColor = Color.White.copy(alpha = 0.1f),
            disabledContentColor   = Color.White.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        AnimatedContent(
            targetState = when {
                isLoading && !isRunning -> "loading_start"
                isLoading && isRunning  -> "loading_stop"
                isRunning               -> "running"
                else                    -> "stopped"
            },
            label = "button_state"
        ) { state ->
            Text(
                text = when (state) {
                    "loading_start" -> "Cargando modelo…"
                    "loading_stop"  -> "Deteniendo…"
                    "running"       -> "Detener Servidor"
                    else            -> "Iniciar Servidor"
                },
                fontWeight = FontWeight.Bold,
                fontSize   = 15.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tema y utilidades
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LlmServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content     = content
    )
}

/**
 * Obtiene la dirección IPv4 local del dispositivo.
 * Prefiere la interfaz wlan0 (Wi-Fi).
 */
fun getLocalIpAddress(): String? {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        interfaces
            .filter { !it.isLoopback && it.isUp }
            .sortedByDescending { it.name.startsWith("wlan") }   // Preferir Wi-Fi
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
            }
            .firstOrNull()
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
