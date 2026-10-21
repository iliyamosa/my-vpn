package app.vpn.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vpn.R
import app.vpn.client.vpn.VpnStatus
import app.vpn.ui.AppColors
import app.vpn.ui.rememberFrameTime

/** A raised, glossy 3D power button that sinks when pressed and tilts with the phone. */
@Composable
fun ConnectButton3D(
    status: VpnStatus,
    tiltX: Float,
    tiltY: Float,
    onClick: () -> Unit,
    size: Dp = 156.dp,
) {
    val base by animateColorAsState(
        when (status) {
            VpnStatus.CONNECTED -> AppColors.Connected
            VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> AppColors.Connecting
            VpnStatus.DISCONNECTED -> AppColors.Primary
        },
        tween(600),
        label = "btnBase",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateDpAsState(if (pressed) 3.dp else 10.dp, spring(dampingRatio = 0.5f, stiffness = 900f), label = "depth")
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING
    val time by rememberFrameTime()

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                rotationX = -tiltX * 1.4f
                rotationY = tiltY * 1.4f
                cameraDistance = 14f * density
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = status != VpnStatus.DISCONNECTING,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val r = this.size.minDimension / 2f * 0.74f
            val d = depth.toPx()
            val t = time

            // Expanding rings while connecting, steady glow while connected
            if (busy) {
                for (k in 0 until 3) {
                    val p = ((t * 0.7f) + k / 3f) % 1f
                    drawCircle(base.copy(alpha = 0.45f * (1f - p)), r * (1.05f + 0.32f * p), Offset(cx, cy), style = Stroke(2.5f * density))
                }
            }
            drawCircle(
                Brush.radialGradient(listOf(base.copy(alpha = 0.45f), Color.Transparent), Offset(cx, cy + d), r * 1.45f),
                r * 1.45f,
                Offset(cx, cy + d),
            )
            // Ground shadow
            drawOval(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), Offset(cx, cy + r * 1.02f), r * 0.9f),
                topLeft = Offset(cx - r * 0.95f, cy + r * 0.78f),
                size = Size(r * 1.9f, r * 0.5f),
            )
            // Metal bezel
            drawCircle(
                Brush.linearGradient(listOf(Color(0xFF475569), Color(0xFF1E293B), Color(0xFF0F172A)), Offset(cx, cy - r * 1.15f), Offset(cx, cy + r * 1.15f)),
                r * 1.13f,
                Offset(cx, cy + 4f * density),
            )
            drawCircle(Color.Black.copy(alpha = 0.45f), r * 1.03f, Offset(cx, cy + 4f * density))
            // Side wall (gives the button its thickness)
            drawCircle(darken(base, 0.45f), r, Offset(cx, cy + 10f * density))
            // Top face, lit from the top-left
            val faceY = cy + (10f * density - d)
            drawCircle(
                Brush.radialGradient(
                    listOf(lighten(base, 0.35f), base, darken(base, 0.25f)),
                    Offset(cx - r * 0.35f, faceY - r * 0.45f),
                    r * 1.6f,
                ),
                r,
                Offset(cx, faceY),
            )
            // Glossy highlight
            drawOval(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.0f)), faceY - r * 0.92f, faceY - r * 0.05f),
                topLeft = Offset(cx - r * 0.62f, faceY - r * 0.9f),
                size = Size(r * 1.24f, r * 0.85f),
            )
            drawCircle(Color.White.copy(alpha = 0.18f), r, Offset(cx, faceY), style = Stroke(1.2f * density))
        }
        val faceShift = 10.dp - depth
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = faceShift - 2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_power),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.3f),
            )
            Text(
                when (status) {
                    VpnStatus.CONNECTED -> "قطع"
                    VpnStatus.CONNECTING -> "لغو"
                    VpnStatus.DISCONNECTING -> "…"
                    VpnStatus.DISCONNECTED -> "اتصال"
                },
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
            )
        }
    }
}

private fun lighten(c: Color, f: Float) = Color(c.red + (1f - c.red) * f, c.green + (1f - c.green) * f, c.blue + (1f - c.blue) * f, c.alpha)

private fun darken(c: Color, f: Float) = Color(c.red * (1f - f), c.green * (1f - f), c.blue * (1f - f), c.alpha)
