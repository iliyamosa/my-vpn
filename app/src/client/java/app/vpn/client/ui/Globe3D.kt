package app.vpn.client.ui

import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.vpn.ui.AppColors
import app.vpn.ui.rememberFrameTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 3D globe made of glowing land dots. It turns slowly; when connected it swings to show an
 * arc from Iran to the server's country with a pulse travelling along it.
 */
@Composable
fun Globe3D(
    connected: Boolean,
    busy: Boolean,
    serverName: String,
    tiltX: Float,
    tiltY: Float,
    modifier: Modifier = Modifier,
) {
    val points = remember { GlobeData.landPoints }
    val home = remember { GlobeData.vector(GlobeData.TEHRAN_LAT, GlobeData.TEHRAN_LON) }
    val target = remember(serverName) {
        GlobeData.locate(serverName)?.let { GlobeData.vector(it[0], it[1]) } ?: GlobeData.vector(50.1f, 8.7f)
    }
    val arcLonDeg = remember(serverName) {
        val t = GlobeData.locate(serverName) ?: floatArrayOf(50.1f, 8.7f)
        midLongitude(GlobeData.TEHRAN_LON, t[1])
    }
    val time by rememberFrameTime()
    val lock by animateFloatAsState(if (connected) 1f else 0f, tween(1600), label = "globeLock")
    val tint by animateColorAsState(
        when {
            connected -> AppColors.Connected
            busy -> AppColors.Connecting
            else -> AppColors.Primary
        },
        tween(800),
        label = "globeTint",
    )
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    val buckets = remember { Array(5) { FloatArray(points.size / 3 * 2) } }
    val counts = remember { IntArray(5) }

    Canvas(modifier) {
        val t = time
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(size.width, size.height) * 0.40f

        // Rotation: free spin, or locked on the connection arc with a gentle sway.
        val free = -55f - t * 7f
        val locked = -arcLonDeg + 12f * sin(t * 0.3f)
        var delta = ((free - locked) % 360f + 540f) % 360f - 180f
        delta *= (1f - lock)
        val yaw = Math.toRadians((locked + delta + tiltY * 1.5f).toDouble()).toFloat()
        val pitch = Math.toRadians((18f + tiltX * 1.2f).toDouble()).toFloat()
        val cyaw = cos(yaw)
        val syaw = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)

        fun project(x: Float, y: Float, z: Float, scale: Float, out: FloatArray) {
            val x1 = x * cyaw + z * syaw
            val z1 = -x * syaw + z * cyaw
            val y2 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            out[0] = cx + x1 * r * scale
            out[1] = cy - y2 * r * scale
            out[2] = z2
        }

        // Halo and atmosphere
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.32f), tint.copy(alpha = 0.08f), Color.Transparent), Offset(cx, cy), r * 1.45f),
            r * 1.45f,
            Offset(cx, cy),
        )
        // Sphere body lit from the top-left
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF243B78), Color(0xFF111C40), Color(0xFF060B1C)),
                Offset(cx - r * 0.35f, cy - r * 0.4f),
                r * 1.5f,
            ),
            r,
            Offset(cx, cy),
        )

        // Land dots, grouped by depth so near dots are brighter and bigger.
        java.util.Arrays.fill(counts, 0)
        val tmp = FloatArray(3)
        var i = 0
        while (i < points.size) {
            project(points[i], points[i + 1], points[i + 2], 1f, tmp)
            val z = tmp[2]
            val b = when {
                z < -0.05f -> 0
                z < 0.3f -> 1
                z < 0.6f -> 2
                z < 0.85f -> 3
                else -> 4
            }
            val arr = buckets[b]
            val c = counts[b]
            arr[c] = tmp[0]
            arr[c + 1] = tmp[1]
            counts[b] = c + 2
            i += 3
        }
        val dot = density
        val alphas = floatArrayOf(0.07f, 0.35f, 0.6f, 0.82f, 1f)
        val sizes = floatArrayOf(1.1f, 1.5f, 1.9f, 2.2f, 2.5f)
        val landColor = lerpColor(Color(0xFF93C5FD), tint, 0.45f)
        drawIntoCanvas { canvas ->
            for (bk in 0 until 5) {
                if (counts[bk] == 0) continue
                paint.color = landColor.copy(alpha = alphas[bk]).toArgb()
                paint.strokeWidth = sizes[bk] * dot
                canvas.nativeCanvas.drawPoints(buckets[bk], 0, counts[bk], paint)
            }
        }

        // Rim light
        drawCircle(
            Brush.sweepGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent, tint.copy(alpha = 0.35f), Color.Transparent, Color.White.copy(alpha = 0.25f)), Offset(cx, cy)),
            r,
            Offset(cx, cy),
            style = Stroke(width = 1.2f * dot),
        )

        // Home marker (Iran)
        project(home[0], home[1], home[2], 1f, tmp)
        if (tmp[2] > 0f) {
            val pulse = (t * 0.8f) % 1f
            drawCircle(Color.White.copy(alpha = 0.5f * (1f - pulse)), (4f + 10f * pulse) * dot, Offset(tmp[0], tmp[1]), style = Stroke(1.5f * dot))
            drawCircle(Color.White, 3.2f * dot, Offset(tmp[0], tmp[1]))
        }

        // Connection arc
        if (lock > 0.02f) {
            val steps = 48
            val pts = ArrayList<FloatArray>(steps + 1)
            val omega = acos((home[0] * target[0] + home[1] * target[1] + home[2] * target[2]).coerceIn(-1f, 1f))
            for (s in 0..steps) {
                val f = s / steps.toFloat()
                val v = slerp(home, target, omega, f)
                val lift = 1f + 0.22f * sin(PI.toFloat() * f) * lock
                val o = FloatArray(3)
                project(v[0], v[1], v[2], lift, o)
                // Hidden when behind the globe
                val dx = o[0] - cx
                val dy = o[1] - cy
                val behind = o[2] < 0f && dx * dx + dy * dy < r * r
                pts.add(if (behind) floatArrayOf(Float.NaN, Float.NaN, o[2]) else o)
            }
            val path = Path()
            var started = false
            for (p in pts) {
                if (p[0].isNaN()) {
                    started = false
                    continue
                }
                if (!started) {
                    path.moveTo(p[0], p[1])
                    started = true
                } else {
                    path.lineTo(p[0], p[1])
                }
            }
            drawPath(path, tint.copy(alpha = 0.25f * lock), style = Stroke(7f * dot, cap = StrokeCap.Round))
            drawPath(path, tint.copy(alpha = 0.95f * lock), style = Stroke(2.2f * dot, cap = StrokeCap.Round))
            // Travelling pulse
            val pf = (t * 0.45f) % 1f
            val idx = (pf * steps).toInt().coerceIn(0, steps)
            val pp = pts[idx]
            if (!pp[0].isNaN()) {
                drawCircle(Color.White.copy(alpha = lock), 3.5f * dot, Offset(pp[0], pp[1]))
                drawCircle(tint.copy(alpha = 0.35f * lock), 9f * dot, Offset(pp[0], pp[1]))
            }
            // Server marker
            val end = pts.last()
            if (!end[0].isNaN()) {
                val pulse = (t * 1.1f) % 1f
                drawCircle(tint.copy(alpha = 0.7f * (1f - pulse) * lock), (5f + 14f * pulse) * dot, Offset(end[0], end[1]), style = Stroke(2f * dot))
                drawCircle(tint.copy(alpha = lock), 4.5f * dot, Offset(end[0], end[1]))
                drawCircle(Color.White.copy(alpha = lock), 2f * dot, Offset(end[0], end[1]))
            }
        }
    }
}

private fun slerp(a: FloatArray, b: FloatArray, omega: Float, t: Float): FloatArray {
    if (omega < 1e-4f) return a
    val so = sin(omega)
    val wa = sin((1f - t) * omega) / so
    val wb = sin(t * omega) / so
    val x = a[0] * wa + b[0] * wb
    val y = a[1] * wa + b[1] * wb
    val z = a[2] * wa + b[2] * wb
    val len = sqrt(x * x + y * y + z * z)
    return floatArrayOf(x / len, y / len, z / len)
}

private fun midLongitude(a: Float, b: Float): Float {
    var d = b - a
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return a + d / 2f
}

private fun lerpColor(a: Color, b: Color, f: Float): Color = Color(
    red = a.red + (b.red - a.red) * f,
    green = a.green + (b.green - a.green) * f,
    blue = a.blue + (b.blue - a.blue) * f,
    alpha = 1f,
)
