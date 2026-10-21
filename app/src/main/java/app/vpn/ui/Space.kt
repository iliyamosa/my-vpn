package app.vpn.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private class Star(val x: Float, val y: Float, val r: Float, val alpha: Float, val speed: Float, val twinkle: Float, val phase: Float)

/** Seconds since the composable appeared, updated every frame (read it only while drawing). */
@Composable
fun rememberFrameTime(): State<Float> = produceState(0f) {
    val start = withFrameNanos { it }
    while (true) {
        withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
}

/** Animated starfield with soft nebula lights; the light turns green while connected. */
@Composable
fun SpaceBackground(connected: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val stars = remember {
        val rnd = Random(7)
        List(150) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                r = 0.6f + rnd.nextFloat() * rnd.nextFloat() * 2.2f,
                alpha = 0.25f + rnd.nextFloat() * 0.75f,
                speed = 0.002f + rnd.nextFloat() * 0.006f,
                twinkle = 0.6f + rnd.nextFloat() * 2.2f,
                phase = rnd.nextFloat() * 2f * PI.toFloat(),
            )
        }
    }
    val time by rememberFrameTime()
    val tint by animateColorAsState(if (connected) AppColors.Connected else AppColors.Primary, tween(1200), label = "spaceTint")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val t = time
        drawRect(Brush.verticalGradient(listOf(Color(0xFF060A16), Color(0xFF0A1028), Color(0xFF070A18))))
        // Nebula lights
        val c1 = Offset(w * (0.75f + 0.06f * sin(t * 0.07f)), h * (0.18f + 0.03f * sin(t * 0.05f)))
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.20f), Color.Transparent), c1, w * 0.85f), w * 0.85f, c1)
        val c2 = Offset(w * (0.15f + 0.05f * sin(t * 0.06f + 1f)), h * (0.78f + 0.03f * sin(t * 0.04f)))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7C3AED).copy(alpha = 0.16f), Color.Transparent), c2, w * 0.9f), w * 0.9f, c2)
        val c3 = Offset(w * 0.5f, h * 0.45f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF0EA5E9).copy(alpha = 0.06f), Color.Transparent), c3, w * 0.7f), w * 0.7f, c3)
        // Stars drift slowly and twinkle
        for (s in stars) {
            val x = ((s.x + t * s.speed) % 1f) * w
            val y = s.y * h
            val tw = 0.5f + 0.5f * sin(t * s.twinkle + s.phase)
            drawCircle(Color.White.copy(alpha = (s.alpha * (0.35f + 0.65f * tw)).coerceIn(0f, 1f)), s.r * density / 2f, Offset(x, y))
        }
    }
}

/**
 * Phone tilt in degrees (x = forward/back, y = left/right), smoothed and limited.
 * Used to give the 3D globe, button and cards a slight parallax.
 */
@Composable
fun rememberDeviceTilt(maxDegrees: Float = 10f): State<Offset> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var fx = 0f
        var fy = 0f
        var baseX: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                // Treat the first reading as "neutral" so any holding angle looks straight.
                val bx = baseX ?: gy.also { baseX = it }
                val tx = ((gy - bx) / 9.81f * 35f).coerceIn(-maxDegrees, maxDegrees)
                val ty = (-gx / 9.81f * 35f).coerceIn(-maxDegrees, maxDegrees)
                fx += (tx - fx) * 0.08f
                fy += (ty - fy) * 0.08f
                // slowly re-center
                baseX = bx + (gy - bx) * 0.002f
                state.value = Offset(fx, fy)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
    return state
}
