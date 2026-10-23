package app.vpn.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.vpn.VpnStatus
import app.vpn.ui.AppColors

/**
 * The FAIROS connect element: a calm, branded orb with the shield at its center.
 * - idle: slow breathing, blue→violet gradient ring
 * - connecting: concentric pulses expand outward
 * - connected: steady mint glow
 */
@Composable
fun ConnectButton(status: VpnStatus, onClick: () -> Unit, size: Dp = 210.dp) {
    val connected = status == VpnStatus.CONNECTED
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    val breath by t.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")

    val accent by animateColorAsState(
        when {
            connected -> AppColors.Connected
            busy -> AppColors.Violet
            else -> AppColors.Blue
        },
        tween(700), label = "accent",
    )
    val ringBrush = if (connected) {
        Brush.sweepGradient(listOf(AppColors.Connected, Color(0xFF5BE0FF), AppColors.Connected))
    } else {
        Brush.sweepGradient(listOf(AppColors.Blue, AppColors.Violet, AppColors.Magenta, AppColors.Blue))
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = status != VpnStatus.DISCONNECTING,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // ambient glow + connecting pulses
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r = this.size.minDimension / 2
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent), c, r),
                radius = r, center = c,
            )
            if (busy) {
                for (i in 0 until 3) {
                    val p = ((pulse + i / 3f) % 1f)
                    drawCircle(accent.copy(alpha = 0.4f * (1f - p)), radius = r * (0.55f + 0.45f * p), center = c, style = Stroke(width = 3f))
                }
            }
        }
        // rotating gradient ring
        Canvas(
            Modifier
                .size(size * 0.82f)
                .rotate(spin),
        ) {
            drawCircle(ringBrush, radius = this.size.minDimension / 2, style = Stroke(width = 6f))
        }
        // inner disc with the shield
        Box(
            modifier = Modifier
                .size(size * 0.62f)
                .scale(if (busy) 1f else breath)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size * 0.62f)) {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                drawCircle(
                    Brush.radialGradient(
                        listOf(AppColors.SurfaceHigh, AppColors.Surface, AppColors.Background),
                        Offset(c.x - this.size.width * 0.15f, c.y - this.size.height * 0.2f),
                        this.size.minDimension * 0.75f,
                    ),
                    radius = this.size.minDimension / 2, center = c,
                )
                drawCircle(accent.copy(alpha = 0.10f), radius = this.size.minDimension / 2, center = c)
            }
            Image(
                painter = painterResource(R.drawable.fairos_logo),
                contentDescription = null,
                modifier = Modifier.size(size * 0.34f),
            )
        }
    }
}
