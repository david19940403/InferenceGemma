package hn.fredi.inferencelocal.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hn.fredi.inferencelocal.UiPhase

// ─────────────────────────────────────────────────────────────────────────────
// Paleta (Internal to UI package or shared)
// ─────────────────────────────────────────────────────────────────────────────
val BG           = Color(0xFF080C10)
val BG2          = Color(0xFF0C1018)
val Surface1     = Color(0xFF111720)
val Surface2     = Color(0xFF16202C)
val BorderSubtle = Color(0xFF1E2D3D)
val BorderActive = Color(0xFF2E4A6A)
val AccentCyan   = Color(0xFF00D4FF)
val AccentGreen  = Color(0xFF00FF9D)
val AccentAmber  = Color(0xFFFFB830)
val AccentRed    = Color(0xFFFF4D6A)
val TextPrimary  = Color(0xFFE8F0F8)
val TextSecond   = Color(0xFF6B8BAE)
val TextMuted    = Color(0xFF304358)

@Composable
fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun StatusOrb(phase: UiPhase) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val rotation by infinite.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label         = "ring_rot"
    )
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

@Composable
fun StateLabel(phase: UiPhase, message: String) {
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

@Composable
fun InfoPill(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
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
fun ConfigSlider(
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

@Composable
fun ErrorCard(detail: String) {
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
