package hn.fredi.inferencelocal.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hn.fredi.inferencelocal.UiPhase

@Composable
fun MainActionButton(
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
