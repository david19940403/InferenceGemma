package hn.fredi.inferencelocal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hn.fredi.inferencelocal.service.LlmServerService
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

// ─────────────────────────────────────────────────────────────────────────────
// Paleta
// ─────────────────────────────────────────────────────────────────────────────
private val BG           = Color(0xFF080C10)
private val BG2          = Color(0xFF0C1018)
private val Surface1     = Color(0xFF111720)
private val Surface2     = Color(0xFF16202C)
private val BorderSubtle = Color(0xFF1E2D3D)
private val BorderActive = Color(0xFF2E4A6A)
private val AccentCyan   = Color(0xFF00D4FF)
private val AccentGreen  = Color(0xFF00FF9D)
private val AccentAmber  = Color(0xFFFFB830)
private val AccentRed    = Color(0xFFFF4D6A)
private val TextPrimary  = Color(0xFFE8F0F8)
private val TextSecond   = Color(0xFF6B8BAE)
private val TextMuted    = Color(0xFF304358)

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                LlmServerScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Estado de UI derivado del broadcast
// ─────────────────────────────────────────────────────────────────────────────

enum class UiPhase { IDLE, LOADING_MODEL, STARTING_SERVER, RUNNING, STOPPING, ERROR }

data class UiState(
    val phase      : UiPhase  = UiPhase.IDLE,
    val model      : String   = "",
    val port       : Int      = LlmServerService.DEFAULT_PORT,
    val message    : String   = "Servidor detenido",
    val errorDetail: String?  = null,
    val sessions   : Int      = 0,
    val tokens     : Long     = 0L,
    val maxTokens  : Int      = 12000,
    val backend    : String   = "N/A",
    val tps        : Double   = 0.0,
    val ttft       : Long     = 0L,
    val initTime   : Long     = 0L,
    val ram        : String   = "—",
    val minRam     : Int      = 1536,
    val defTemp    : Float    = 0.7f,
    val defTopK    : Int      = 40,
    val defTopP    : Float    = 0.9f,
    val defNumCtx  : Int      = 4096,
    val prefBackend: String   = "GPU",
    val maxSessions: Int      = 1
)

// ─────────────────────────────────────────────────────────────────────────────
// Pantalla principal
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LlmServerScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Consider landscape orientation as tablet-like for better space utilization
    val isTablet = configuration.screenWidthDp >= 600 || configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val localIp = remember { getLocalIpAddress() ?: "—" }
    val scroll = rememberScrollState()

    var ui by remember { mutableStateOf(UiState()) }
    var modelSelected by remember {
        mutableStateOf(
            getAvailableModels(context).firstOrNull() ?: LlmServerService.DEFAULT_MODEL_FILENAME
        )
    }

    // Estados para configuración por defecto
    var configTemp by remember { mutableFloatStateOf(0.7f) }
    var configTopK by remember { mutableIntStateOf(40) }
    var configTopP by remember { mutableFloatStateOf(0.9f) }
    var configNumCtx by remember { mutableIntStateOf(4096) }
    var prefBackend by remember { mutableStateOf("GPU") }
    var maxSessions by remember { mutableIntStateOf(3) }
    var minRamMb by remember { mutableIntStateOf(1536) }

    // ── Broadcast receiver ───────────────────────────────────────────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val code = intent.getStringExtra(LlmServerService.EXTRA_STATE_CODE) ?: return
                val msg = intent.getStringExtra(LlmServerService.EXTRA_STATE_MSG) ?: ""
                val err = intent.getStringExtra(LlmServerService.EXTRA_STATE_ERROR)
                val port = intent.getIntExtra(
                    LlmServerService.EXTRA_STATE_PORT,
                    LlmServerService.DEFAULT_PORT
                )
                val model = intent.getStringExtra(LlmServerService.EXTRA_STATE_MODEL) ?: ""
                val sessions = intent.getIntExtra(LlmServerService.EXTRA_STATE_SESSIONS, 0)
                val tokens = intent.getLongExtra(LlmServerService.EXTRA_STATE_TOKENS, 0L)
                val maxTokens = intent.getIntExtra(LlmServerService.EXTRA_STATE_MAX_TOKENS, 2048)
                val backend = intent.getStringExtra(LlmServerService.EXTRA_STATE_BACKEND) ?: "N/A"
                val tps = intent.getDoubleExtra(LlmServerService.EXTRA_STATE_TPS, 0.0)
                val ttft = intent.getLongExtra(LlmServerService.EXTRA_STATE_TTFT, 0L)
                val initTime = intent.getLongExtra(LlmServerService.EXTRA_STATE_INIT_TIME, 0L)
                val ram = intent.getStringExtra(LlmServerService.EXTRA_STATE_RAM) ?: "—"

                // Configuración
                val dTemp = intent.getFloatExtra(LlmServerService.EXTRA_DEFAULT_TEMP, 0.7f)
                val dTopK = intent.getIntExtra(LlmServerService.EXTRA_DEFAULT_TOP_K, 40)
                val dTopP = intent.getFloatExtra(LlmServerService.EXTRA_DEFAULT_TOP_P, 0.9f)
                val dNumCtx = intent.getIntExtra(LlmServerService.EXTRA_DEFAULT_NUM_CTX, 2048)
                val pb = intent.getStringExtra(LlmServerService.EXTRA_PREFERRED_BACKEND) ?: "GPU"
                val ms = intent.getIntExtra(LlmServerService.EXTRA_MAX_SESSIONS, 3)
                val minR = intent.getIntExtra(LlmServerService.EXTRA_MIN_RAM_MB, 1536)

                ui = when (code) {
                    "LOADING_MODEL" -> UiState(
                        UiPhase.LOADING_MODEL,
                        model,
                        port,
                        msg,
                        null,
                        sessions,
                        tokens,
                        maxTokens,
                        backend,
                        tps,
                        ttft,
                        initTime,
                        ram,
                        minR,
                        dTemp,
                        dTopK,
                        dTopP,
                        dNumCtx,
                        pb,
                        ms
                    )

                    "STARTING_SERVER" -> UiState(
                        UiPhase.STARTING_SERVER,
                        model,
                        port,
                        msg,
                        null,
                        sessions,
                        tokens,
                        maxTokens,
                        backend,
                        tps,
                        ttft,
                        initTime,
                        ram,
                        minR,
                        dTemp,
                        dTopK,
                        dTopP,
                        dNumCtx,
                        pb,
                        ms
                    )

                    "RUNNING" -> UiState(
                        UiPhase.RUNNING,
                        model,
                        port,
                        msg,
                        null,
                        sessions,
                        tokens,
                        maxTokens,
                        backend,
                        tps,
                        ttft,
                        initTime,
                        ram,
                        minR,
                        dTemp,
                        dTopK,
                        dTopP,
                        dNumCtx,
                        pb,
                        ms
                    )

                    "STOPPING" -> UiState(
                        UiPhase.STOPPING,
                        model,
                        port,
                        msg,
                        null,
                        sessions,
                        tokens,
                        maxTokens,
                        backend,
                        tps,
                        ttft,
                        initTime,
                        ram,
                        minR,
                        dTemp,
                        dTopK,
                        dTopP,
                        dNumCtx,
                        pb,
                        ms
                    )

                    "STOPPED" -> UiState(UiPhase.IDLE, model, port, "Servidor detenido", null, 0, 0, 4096)
                    "ERROR" -> UiState(
                        UiPhase.ERROR,
                        model,
                        port,
                        msg,
                        err,
                        sessions,
                        tokens,
                        maxTokens,
                        backend,
                        tps,
                        ttft,
                        initTime,
                        ram,
                        minR,
                        dTemp,
                        dTopK,
                        dTopP,
                        dNumCtx,
                        pb,
                        ms
                    )

                    else -> ui
                }
            }
        }

        val filter = IntentFilter(LlmServerService.BROADCAST_STATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(Unit) {
        delay(500)
        val queryIntent = Intent(context, LlmServerService::class.java).apply {
            action = LlmServerService.ACTION_QUERY_STATE
        }
        context.startService(queryIntent)
    }
    val isRunning = ui.phase == UiPhase.RUNNING
    val isBusy = ui.phase == UiPhase.LOADING_MODEL ||
            ui.phase == UiPhase.STARTING_SERVER ||
            ui.phase == UiPhase.STOPPING

    // ── Fondo con malla de puntos ─────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .drawBehind {
                val step = 36.dp.toPx()
                val dotR = 1.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    var y = 0f
                    while (y < size.height) {
                        drawCircle(
                            color = Color(0xFF1A2A3A),
                            radius = dotR,
                            center = Offset(x, y)
                        )
                        y += step
                    }
                    x += step
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AccentCyan.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.width * 0.8f
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.5f, 0f)
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = if (isTablet) 40.dp else 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Header()

            if (isTablet) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Columna Izquierda: Estado y Estadísticas
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StatusOrb(phase = ui.phase)
                        StateLabel(phase = ui.phase, message = ui.message)
                        ConnectionCard(ip = localIp, port = ui.port, isRunning = isRunning)
                        AnimatedVisibility(visible = isRunning) {
                            StatsCard(ui)
                        }
                    }

                    // Columna Derecha: Configuración y Acción
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ModelSelectorCard(
                            selectedModel = modelSelected,
                            isDisabled = isRunning || isBusy,
                            onSelected = { modelSelected = it }
                        )

                        AnimatedVisibility(visible = !isRunning && !isBusy) {
                            ModelConfigCard(
                                temp = configTemp,
                                onTempChange = { configTemp = it },
                                topK = configTopK,
                                onTopKChange = { configTopK = it },
                                topP = configTopP,
                                onTopPChange = { configTopP = it },
                                numCtx = configNumCtx,
                                onNumCtxChange = { configNumCtx = it },
                                prefBackend = prefBackend,
                                onPrefBackendChange = { prefBackend = it },
                                maxSessions = maxSessions,
                                onMaxSessionsChange = { maxSessions = it },
                                minRamMb = minRamMb,
                                onMinRamMbChange = { minRamMb = it }
                            )
                        }

                        AnimatedVisibility(visible = ui.phase == UiPhase.ERROR) {
                            ErrorCard(detail = ui.errorDetail ?: ui.message)
                        }

                        MainActionButton(
                            phase = ui.phase,
                            isBusy = isBusy,
                            onClick = {
                                if (isRunning || ui.phase == UiPhase.ERROR) {
                                    context.startService(LlmServerService.stopIntent(context))
                                    ui = ui.copy(phase = UiPhase.STOPPING, message = "Deteniendo…")
                                } else if (!isBusy) {
                                    val intent = LlmServerService.startIntent(
                                        context = context,
                                        modelName = modelSelected,
                                        numCtx = configNumCtx,
                                        temp = configTemp,
                                        topK = configTopK,
                                        topP = configTopP,
                                        preferredBackend = prefBackend,
                                        maxSessions = maxSessions,
                                        minRamMb = minRamMb
                                    )
                                    context.startForegroundService(intent)
                                    ui = ui.copy(phase = UiPhase.LOADING_MODEL, message = "Cargando modelo…")
                                }
                            }
                        )

                        AnimatedVisibility(visible = isRunning) {
                            Text(
                                text = "Compatible con clientes Ollama · OpenAI API",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // Vista móvil (existente)
                StatusOrb(phase = ui.phase)
                StateLabel(phase = ui.phase, message = ui.message)
                ConnectionCard(ip = localIp, port = ui.port, isRunning = isRunning)
                AnimatedVisibility(visible = isRunning) {
                    StatsCard(ui)
                }
                ModelSelectorCard(
                    selectedModel = modelSelected,
                    isDisabled = isRunning || isBusy,
                    onSelected = { modelSelected = it }
                )
                AnimatedVisibility(visible = !isRunning && !isBusy) {
                    ModelConfigCard(
                        temp = configTemp,
                        onTempChange = { configTemp = it },
                        topK = configTopK,
                        onTopKChange = { configTopK = it },
                        topP = configTopP,
                        onTopPChange = { configTopP = it },
                        numCtx = configNumCtx,
                        onNumCtxChange = { configNumCtx = it },
                        prefBackend = prefBackend,
                        onPrefBackendChange = { prefBackend = it },
                        maxSessions = maxSessions,
                        onMaxSessionsChange = { maxSessions = it },
                        minRamMb = minRamMb,
                        onMinRamMbChange = { minRamMb = it }
                    )
                }
                AnimatedVisibility(visible = ui.phase == UiPhase.ERROR) {
                    ErrorCard(detail = ui.errorDetail ?: ui.message)
                }
                MainActionButton(
                    phase = ui.phase,
                    isBusy = isBusy,
                    onClick = {
                        if (isRunning || ui.phase == UiPhase.ERROR) {
                            context.startService(LlmServerService.stopIntent(context))
                            ui = ui.copy(phase = UiPhase.STOPPING, message = "Deteniendo…")
                        } else if (!isBusy) {
                            val intent = LlmServerService.startIntent(
                                context = context,
                                modelName = modelSelected,
                                numCtx = configNumCtx,
                                temp = configTemp,
                                topK = configTopK,
                                topP = configTopP,
                                preferredBackend = prefBackend,
                                maxSessions = maxSessions,
                                minRamMb = minRamMb
                            )
                            context.startForegroundService(intent)
                            ui = ui.copy(phase = UiPhase.LOADING_MODEL, message = "Cargando modelo…")
                        }
                    }
                )
                AnimatedVisibility(visible = isRunning) {
                    Text(
                        text = "Compatible con clientes Ollama · OpenAI API",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Chip "ON-DEVICE"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AccentCyan.copy(alpha = 0.1f))
                    .border(1.dp, AccentCyan.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text          = "ON-DEVICE",
                    color         = AccentCyan,
                    fontSize      = 9.sp,
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text          = "InferenceLocal",
            fontSize      = 30.sp,
            fontWeight    = FontWeight.Black,
            color         = TextPrimary,
            letterSpacing = (-0.5).sp
        )
        Text(
            text          = "Direccion de desarrollo by Fredi Codificado por Geminy, Claude, Agradecimientos especiales a la documentacion de google y a los ejemplos faciles de leer.",
            fontSize      = 13.sp,
            fontWeight    = FontWeight.Normal,
            color         = TextSecond,
            letterSpacing = 0.5.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Orb de estado con animaciones
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusOrb(phase: UiPhase) {
    val infinite = rememberInfiniteTransition(label = "orb")

    // Rotación del anillo exterior para estado "busy"
    val rotation by infinite.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label         = "ring_rot"
    )

    // Pulso para estado running
    val pulse by infinite.animateFloat(
        initialValue  = 0.85f,
        targetValue   = 1.15f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "pulse"
    )

    val isBusy    = phase == UiPhase.LOADING_MODEL || phase == UiPhase.STARTING_SERVER || phase == UiPhase.STOPPING
    val isRunning = phase == UiPhase.RUNNING
    val isError   = phase == UiPhase.ERROR

    val coreColor = when {
        isError   -> AccentRed
        isBusy    -> AccentAmber
        isRunning -> AccentGreen
        else      -> TextMuted
    }

    Box(
        modifier          = Modifier.size(120.dp),
        contentAlignment  = Alignment.Center
    ) {
        // Halo externo difuso
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            coreColor.copy(alpha = if (isRunning) 0.12f * pulse else 0.07f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Anillo giratorio (solo cuando busy)
        if (isBusy) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .rotate(rotation)
                    .drawBehind {
                        drawArc(
                            color      = AccentAmber.copy(alpha = 0.7f),
                            startAngle = 0f,
                            sweepAngle = 260f,
                            useCenter  = false,
                            style      = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
            )
        }

        // Anillo estático (running)
        if (isRunning) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .drawBehind {
                        drawCircle(
                            color  = AccentGreen.copy(alpha = 0.25f),
                            style  = Stroke(width = 1.dp.toPx())
                        )
                    }
            )
        }

        // Núcleo
        Box(
            modifier         = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Surface1)
                .border(1.dp, coreColor.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(coreColor, coreColor.copy(alpha = 0.4f))
                        )
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Etiqueta de estado con transición
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StateLabel(phase: UiPhase, message: String) {
    val color = when (phase) {
        UiPhase.RUNNING        -> AccentGreen
        UiPhase.ERROR          -> AccentRed
        UiPhase.LOADING_MODEL,
        UiPhase.STARTING_SERVER,
        UiPhase.STOPPING       -> AccentAmber
        else                   -> TextSecond
    }

    AnimatedContent(
        targetState   = message,
        transitionSpec = {
            (fadeIn(tween(300)) + slideInVertically { it / 2 })
                .togetherWith(fadeOut(tween(200)))
        },
        label = "state_label"
    ) { msg ->
        Text(
            text       = msg,
            color      = color,
            fontSize   = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            textAlign  = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tarjeta de conexión
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(ip: String, port: Int, isRunning: Boolean) {
    val context   = LocalContext.current
    val curlCmd   = """curl -X POST http://$ip:$port/api/generate -H "Content-Type: application/json" -d '{"model":"gemma-2b-it","prompt":"Hola"}'"""

    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = Surface1,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Título de sección
            Text(
                text          = "CONEXIÓN",
                color         = TextMuted,
                fontSize      = 10.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // IP y Puerto
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(label = "IP", value = ip, accent = AccentCyan, modifier = Modifier.weight(1f))
                InfoPill(label = "PUERTO", value = port.toString(), accent = AccentCyan, modifier = Modifier.weight(0.6f))
            }

            // Endpoints
            HorizontalDivider(color = BorderSubtle)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EndpointChip("/api/generate", AccentGreen)
                EndpointChip("/api/chat",     AccentGreen)
                EndpointChip("/v1/...",        AccentCyan)
            }

            // cURL (solo cuando está corriendo)
            AnimatedVisibility(
                visible = isRunning,
                enter   = fadeIn() + expandVertically(),
                exit    = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = BorderSubtle)
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            text          = "CURL DE PRUEBA",
                            color         = TextMuted,
                            fontSize      = 9.sp,
                            fontFamily    = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                        IconButton(
                            onClick  = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("curl", curlCmd))
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Copiar",
                                tint               = TextSecond,
                                modifier           = Modifier.size(14.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BG)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text       = curlCmd,
                            color      = Color(0xFF7ECAFF),
                            fontSize   = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                            maxLines   = 4,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(ui: UiState) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Surface1,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECURSOS Y RENDIMIENTO",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                
                // Badges de Backend
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Requested Backend
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TextMuted.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "PREF: ${ui.prefBackend.uppercase()}",
                            color = TextSecond,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Active Backend
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ACTIVO: ${ui.backend.uppercase()}",
                            color = AccentCyan,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Fila 1: Contexto y Workers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(
                    label = "CONTEXTO",
                    value = "${ui.maxTokens / 1024}K",
                    accent = AccentAmber,
                    modifier = Modifier.weight(1f)
                )
                InfoPill(
                    label = "WORKERS",
                    value = "${ui.sessions}",
                    accent = AccentCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            // Fila 2: Velocidad y Latencia
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(
                    label = "VELOCIDAD",
                    value = if (ui.tps > 0) "${"%.1f".format(ui.tps)} t/s" else "—",
                    accent = AccentGreen,
                    modifier = Modifier.weight(1f)
                )
                InfoPill(
                    label = "TTFT",
                    value = if (ui.ttft > 0) "${ui.ttft}ms" else "—",
                    accent = AccentAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            // Fila 3: Total y Carga
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(
                    label = "CARGA INICIAL",
                    value = if (ui.initTime > 0) "${"%.1f".format(ui.initTime / 1000.0)}s" else "—",
                    accent = TextSecond,
                    modifier = Modifier.weight(1f)
                )
                InfoPill(
                    label = "RAM (JVM | LIBRE)",
                    value = ui.ram,
                    accent = AccentAmber,
                    modifier = Modifier.weight(1f)
                )
            }
            
            InfoPill(
                label = "TOTAL TOKENS GENERADOS",
                value = formatTokens(ui.tokens),
                accent = AccentGreen,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatTokens(tokens: Long): String {
    return when {
        tokens < 1000 -> "$tokens"
        tokens < 1000000 -> "%.1fK".format(tokens / 1000f)
        else -> "%.1fM".format(tokens / 1000000f)
    }
}

@Composable
private fun InfoPill(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BG2)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text          = label,
            color         = TextMuted,
            fontSize      = 9.sp,
            fontFamily    = FontFamily.Monospace,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text       = value,
            color      = accent,
            fontSize   = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EndpointChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text       = label,
            color      = color.copy(alpha = 0.9f),
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Selector de modelo
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModelConfigCard(
    temp: Float,
    onTempChange: (Float) -> Unit,
    topK: Int,
    onTopKChange: (Int) -> Unit,
    topP: Float,
    onTopPChange: (Float) -> Unit,
    numCtx: Int,
    onNumCtxChange: (Int) -> Unit,
    prefBackend: String,
    onPrefBackendChange: (String) -> Unit,
    maxSessions: Int,
    onMaxSessionsChange: (Int) -> Unit,
    minRamMb: Int,
    onMinRamMbChange: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Surface1,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "CONFIGURACIÓN POR DEFECTO",
                color = TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // Backend Selector
            Text(
                text = "Backend Preferido",
                color = TextSecond,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto", "NPU", "GPU", "CPU").forEach { backend ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (prefBackend == backend) AccentCyan.copy(alpha = 0.2f) else BG2)
                            .border(
                                1.dp,
                                if (prefBackend == backend) AccentCyan else BorderSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onPrefBackendChange(backend) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = backend,
                            color = if (prefBackend == backend) AccentCyan else TextSecond,
                            fontSize = 11.sp,
                            fontWeight = if (prefBackend == backend) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            
            HorizontalDivider(color = BorderSubtle)

            // Max Workers
            ConfigSlider(
                label = "Máx Workers (Sesiones RAM)",
                value = maxSessions.toFloat(),
                valueRange = 1f..5f,
                steps = 4,
                displayValue = maxSessions.toString(),
                onValueChange = { onMaxSessionsChange(it.toInt()) }
            )

            // RAM Threshold
            ConfigSlider(
                label = "RAM Mínima (MB) para Carga",
                value = minRamMb.toFloat(),
                valueRange = 512f..4096f,
                steps = 14,
                displayValue = "${minRamMb}MB",
                onValueChange = { onMinRamMbChange(it.toInt()) }
            )

            HorizontalDivider(color = BorderSubtle)

            // Temperature
            ConfigSlider(
                label = "Temperature",
                value = temp,
                valueRange = 0f..2f,
                steps = 20,
                displayValue = "%.2f".format(temp),
                onValueChange = onTempChange
            )

            // Top-P
            ConfigSlider(
                label = "Top-P",
                value = topP,
                valueRange = 0f..1f,
                steps = 10,
                displayValue = "%.2f".format(topP),
                onValueChange = onTopPChange
            )

            // Top-K
            ConfigSlider(
                label = "Top-K",
                value = topK.toFloat(),
                valueRange = 1f..100f,
                steps = 100,
                displayValue = topK.toString(),
                onValueChange = { onTopKChange(it.toInt()) }
            )

            // Num Ctx
            Text(
                text = "Contexto (Tokens): $numCtx",
                color = TextSecond,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048, 4096, 8192, 16384).forEach { size ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (numCtx == size) AccentCyan.copy(alpha = 0.2f) else BG2)
                            .border(
                                1.dp,
                                if (numCtx == size) AccentCyan else BorderSubtle,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onNumCtxChange(size) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (size >= 1024) "${size / 1024}K" else "$size",
                            color = if (numCtx == size) AccentCyan else TextSecond,
                            fontSize = 11.sp,
                            fontWeight = if (numCtx == size) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = TextSecond,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = displayValue,
                color = AccentCyan,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = AccentCyan,
                activeTrackColor = AccentCyan,
                inactiveTrackColor = BorderSubtle
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Selector de modelo
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModelSelectorCard(
    selectedModel : String,
    isDisabled    : Boolean,
    onSelected    : (String) -> Unit
) {
    val context   = LocalContext.current
    val models    = remember { getAvailableModels(context) }
    var expanded  by remember { mutableStateOf(false) }
    val alpha     by animateFloatAsState(if (isDisabled) 0.4f else 1f, label = "model_alpha")

    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = Surface1,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text          = "MODELO",
                color         = TextMuted,
                fontSize      = 10.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BG2)
                    .border(
                        1.dp,
                        if (!isDisabled) BorderActive else BorderSubtle,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(
                        enabled          = !isDisabled && models.isNotEmpty(),
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null
                    ) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text       = if (models.isEmpty()) "¡NO HAY MODELOS!" else selectedModel,
                            color      = if (models.isEmpty()) AccentRed else TextPrimary,
                            fontSize   = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (models.isEmpty()) FontWeight.Bold else FontWeight.Medium,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                        if (models.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text       = "Copia un modelo .bin o .tflite a la carpeta de datos de la app usando ADB o un explorador de archivos.",
                                color      = TextSecond,
                                fontSize   = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text       = "Ruta: /sdcard/Android/data/hn.fredi.inferencelocal/files/",
                                color      = AccentCyan.copy(alpha = 0.7f),
                                fontSize   = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (models.isNotEmpty()) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expandir",
                            tint     = TextSecond,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded          = expanded,
                    onDismissRequest  = { expanded = false }
                ) {
                    models.forEach { name ->
                        DropdownMenuItem(
                            text    = {
                                Text(
                                    text       = name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = 13.sp
                                )
                            },
                            onClick = {
                                onSelected(name)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tarjeta de error
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorCard(detail: String) {
    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = AccentRed.copy(alpha = 0.07f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentRed.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier          = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠", fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text       = detail,
                color      = AccentRed.copy(alpha = 0.9f),
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Botón principal de acción
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MainActionButton(
    phase  : UiPhase,
    isBusy : Boolean,
    onClick: () -> Unit
) {
    val isRunning = phase == UiPhase.RUNNING
    val isError   = phase == UiPhase.ERROR

    val containerColor = when {
        isBusy    -> Surface2
        isRunning -> AccentRed.copy(alpha = 0.9f)
        isError   -> AccentAmber.copy(alpha = 0.9f)
        else      -> AccentGreen
    }
    val contentColor = when {
        isBusy    -> TextMuted
        isRunning -> Color.White
        else      -> Color(0xFF080C10)
    }

    // Animación de opacidad del borde para estado activo
    val infinite    = rememberInfiniteTransition(label = "btn")
    val borderAlpha by infinite.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "border_alpha"
    )

    ElevatedButton(
        onClick  = onClick,
        enabled  = !isBusy,
        shape    = RoundedCornerShape(14.dp),
        colors   = ButtonDefaults.elevatedButtonColors(
            containerColor         = containerColor,
            contentColor           = contentColor,
            disabledContainerColor = Surface2,
            disabledContentColor   = TextMuted
        ),
        elevation = ButtonDefaults.elevatedButtonElevation(
            pressedElevation = 0.dp,
            defaultElevation        = if (isBusy) 0.dp else 4.dp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .then(
                if (isRunning) Modifier.border(
                    1.dp,
                    AccentRed.copy(alpha = borderAlpha),
                    RoundedCornerShape(14.dp)
                ) else Modifier
            )
    ) {
        AnimatedContent(
            targetState   = phase,
            transitionSpec = {
                fadeIn(tween(220)).togetherWith(fadeOut(tween(160)))
            },
            label = "btn_label"
        ) { p ->
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isBusy) {
                    // Spinner minimal
                    val rot by rememberInfiniteTransition(label = "spin").animateFloat(
                        initialValue  = 0f,
                        targetValue   = 360f,
                        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
                        label         = "spin_rot"
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(rot)
                            .drawBehind {
                                drawArc(
                                    color      = AccentAmber,
                                    startAngle = 0f,
                                    sweepAngle = 270f,
                                    useCenter  = false,
                                    style      = Stroke(
                                        width = 2.dp.toPx(),
                                        cap   = StrokeCap.Round
                                    )
                                )
                            }
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = when (p) {
                        UiPhase.LOADING_MODEL   -> "Cargando modelo…"
                        UiPhase.STARTING_SERVER -> "Iniciando servidor…"
                        UiPhase.STOPPING        -> "Deteniendo…"
                        UiPhase.RUNNING         -> "Detener Servidor"
                        UiPhase.ERROR           -> "Reintentar"
                        UiPhase.IDLE            -> "Iniciar Servidor"
                    },
                    fontWeight    = FontWeight.Bold,
                    fontSize      = 15.sp,
                    letterSpacing = 0.3.sp,
                    fontFamily    = FontFamily.Monospace
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tema
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BG,
            surface    = Surface1,
            primary    = AccentCyan,
            onPrimary  = BG,
            onSurface  = TextPrimary
        ),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilidades
// ─────────────────────────────────────────────────────────────────────────────

fun getAvailableModels(context: Context): List<String> =
    context.getExternalFilesDir(null)
        ?.listFiles { f -> f.extension in listOf("bin", "litert", "tflite", "litertlm", "task") }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()

fun getLocalIpAddress(): String? = try {
    NetworkInterface.getNetworkInterfaces()
        ?.toList()
        ?.filter { !it.isLoopback && it.isUp }
        ?.sortedByDescending { it.name.startsWith("wlan") }
        ?.flatMap { it.inetAddresses.toList() }
        ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress
} catch (e: Exception) { null }