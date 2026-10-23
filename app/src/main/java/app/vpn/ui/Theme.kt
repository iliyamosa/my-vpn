package app.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    // FAIROS identity: deep space background, blue -> violet -> magenta brand gradient.
    val Background = Color(0xFF05060D)
    val Surface = Color(0xFF12152A)
    val SurfaceHigh = Color(0xFF1A1E3A)
    val Border = Color(0xFF2A2F55)
    val Blue = Color(0xFF3A7BFF)
    val Violet = Color(0xFF8B6CFF)
    val Magenta = Color(0xFFC04BFF)
    val Primary = Violet
    val PrimaryDark = Color(0xFF5B46C9)
    val Connected = Color(0xFF2FE3B8)
    val Connecting = Color(0xFFF2A93C)
    val Danger = Color(0xFFFF5C7A)
    val Gold = Color(0xFFF3CF7A)
    val TextPrimary = Color(0xFFEEF0FA)
    val TextSecondary = Color(0xFF9AA0C4)
    val TextMuted = Color(0xFF686E96)

    val brand = listOf(Blue, Violet, Magenta)
}

/** The FAIROS blue -> violet -> magenta gradient, for buttons, accents and text. */
fun brandBrush(): Brush = Brush.linearGradient(AppColors.brand)

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

/** The FAIROS shield logo. */
@Composable
fun FairosLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.fairos_logo),
        contentDescription = "FAIROS",
        modifier = modifier.size(size),
    )
}

/** The FAIROS VPN wordmark (keeps its own aspect ratio). */
@Composable
fun FairosWordmark(modifier: Modifier = Modifier, height: Dp = 22.dp) {
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.fairos_wordmark),
        contentDescription = "FAIROS VPN",
        modifier = modifier.height(height),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}

/** Text painted with the brand gradient. */
@Composable
fun GradientText(text: String, style: TextStyle, modifier: Modifier = Modifier, fontWeight: FontWeight? = null) {
    Text(
        text = text,
        style = style.copy(brush = brandBrush()),
        fontWeight = fontWeight,
        modifier = modifier,
    )
}

/** A rounded gradient action button. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (enabled) 10.dp else 0.dp, shape, spotColor = AppColors.Violet.copy(alpha = 0.6f))
            .clip(shape)
            .background(if (enabled) brandBrush() else Brush.linearGradient(listOf(AppColors.SurfaceHigh, AppColors.SurfaceHigh)))
            .then(if (enabled && !loading) Modifier.androidxClick(onClick) else Modifier)
            .padding(vertical = 15.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Text(text, color = if (enabled) Color.White else AppColors.TextMuted, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private fun Modifier.androidxClick(onClick: () -> Unit): Modifier = this.then(
    androidx.compose.foundation.clickable(onClick = onClick),
)

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
