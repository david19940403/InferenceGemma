package hn.fredi.inferencelocal.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hn.fredi.inferencelocal.UiState
import hn.fredi.inferencelocal.getAvailableModels

@Composable
fun ConnectionCard(ip: String, port: Int, isRunning: Boolean) {
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
            Text(
                text          = "CONEXIÓN",
                color         = TextMuted,
                fontSize      = 10.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(label = "IP", value = ip, accent = AccentCyan, modifier = Modifier.weight(1f))
                InfoPill(label = "PUERTO", value = port.toString(), accent = AccentCyan, modifier = Modifier.weight(0.6f))
            }

            HorizontalDivider(color = BorderSubtle)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EndpointChip("/api/generate", AccentGreen)
                EndpointChip("/api/chat",     AccentGreen)
                EndpointChip("/v1/...",        AccentCyan)
            }

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
fun StatsCard(ui: UiState) {
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
                
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
fun EndpointChip(label: String, color: Color) {
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

@Composable
fun ModelSelectorCard(
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

@Composable
fun ModelConfigCard(
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

            ConfigSlider(
                label = "Máx Workers (Sesiones RAM)",
                value = maxSessions.toFloat(),
                valueRange = 1f..5f,
                steps = 4,
                displayValue = maxSessions.toString(),
                onValueChange = { onMaxSessionsChange(it.toInt()) }
            )

            ConfigSlider(
                label = "RAM Mínima (MB) para Carga",
                value = minRamMb.toFloat(),
                valueRange = 512f..4096f,
                steps = 14,
                displayValue = "${minRamMb}MB",
                onValueChange = { onMinRamMbChange(it.toInt()) }
            )

            HorizontalDivider(color = BorderSubtle)

            ConfigSlider(
                label = "Temperature",
                value = temp,
                valueRange = 0f..2f,
                steps = 20,
                displayValue = "%.2f".format(temp),
                onValueChange = onTempChange
            )

            ConfigSlider(
                label = "Top-P",
                value = topP,
                valueRange = 0f..1f,
                steps = 10,
                displayValue = "%.2f".format(topP),
                onValueChange = onTopPChange
            )

            ConfigSlider(
                label = "Top-K",
                value = topK.toFloat(),
                valueRange = 1f..100f,
                steps = 100,
                displayValue = topK.toString(),
                onValueChange = { onTopKChange(it.toInt()) }
            )

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
