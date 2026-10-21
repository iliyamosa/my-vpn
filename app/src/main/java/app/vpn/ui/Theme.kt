package app.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vpn.R

object AppColors {
    val Background = Color(0xFF0B1020)
    val Surface = Color(0xFF141B2E)
    val SurfaceHigh = Color(0xFF1C2540)
    val Border = Color(0xFF26304D)
    val Primary = Color(0xFF3B82F6)
    val PrimaryDark = Color(0xFF1D4ED8)
    val Connected = Color(0xFF22C55E)
    val Connecting = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Gold = Color(0xFFFBBF24)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

val Vazir = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_black, FontWeight.Black),
)

private fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.v() = copy(fontFamily = Vazir)
    return Typography(
        displayLarge = base.displayLarge.v(),
        displayMedium = base.displayMedium.v(),
        displaySmall = base.displaySmall.v(),
        headlineLarge = base.headlineLarge.v(),
        headlineMedium = base.headlineMedium.v(),
        headlineSmall = base.headlineSmall.v(),
        titleLarge = base.titleLarge.v().copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.v().copy(fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.v().copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.v(),
        bodyMedium = base.bodyMedium.v(),
        bodySmall = base.bodySmall.v(),
        labelLarge = base.labelLarge.v().copy(fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.v(),
        labelSmall = base.labelSmall.v(),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = AppColors.Primary,
        onPrimary = Color.White,
        secondary = AppColors.Gold,
        background = AppColors.Background,
        onBackground = AppColors.TextPrimary,
        surface = AppColors.Surface,
        onSurface = AppColors.TextPrimary,
        surfaceVariant = AppColors.SurfaceHigh,
        onSurfaceVariant = AppColors.TextSecondary,
        error = AppColors.Danger,
        outline = AppColors.Border,
    )
    MaterialTheme(colorScheme = colors, typography = appTypography()) {
        // The whole UI is Persian: always right-to-left, whatever the phone language is.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}

/** Raised "glass" card: translucent, with a light edge and a soft colored shadow for depth. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = AppColors.Surface,
    glow: Color = AppColors.Primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = glow.copy(alpha = 0.35f), spotColor = glow.copy(alpha = 0.45f)),
        shape = shape,
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f), glow.copy(alpha = 0.18f))),
        ),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.82f), contentColor = AppColors.TextPrimary),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)))
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun AppIcon(res: Int, tint: Color = AppColors.TextPrimary, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = AppColors.TextSecondary,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(0.dp),
    ) {
        Card(
            shape = RoundedCornerShape(50),
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f), contentColor = color),
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                color = color,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
