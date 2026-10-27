package app.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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

    // Glass surface tint: a cool violet-blue the frosted panes are built from, so the
    // animated background light reads through them without washing out text.
    val GlassBase = Color(0xFF151A36)

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

/**
 * Frosted "glass" pane — the one card every screen is built from.
 *
 * It layers a translucent violet-blue fill, a top-down sheen, a faint colored tint and a
 * fine light-to-glow edge, over a soft colored shadow. The animated [SpaceBackground]
 * shows faintly through it, so it reads as real glass. Runtime backdrop-blur is avoided
 * on purpose (it is costly and janky on weak devices); this translucent build stays smooth
 * everywhere while giving the same frosted look.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = AppColors.GlassBase,
    glow: Color = AppColors.Primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(20.dp, shape, ambientColor = glow.copy(alpha = 0.26f), spotColor = glow.copy(alpha = 0.40f))
            .clip(shape)
            // translucent frosted fill (background light shows through the gap)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.60f), color.copy(alpha = 0.46f))))
            // sheen + faint colored glass tint
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.07f), Color.Transparent, glow.copy(alpha = 0.07f)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f), glow.copy(alpha = 0.22f)),
                ),
                shape,
            ),
    ) {
        // Preserve the text color the old Material Card provided, so prose stays readable.
        CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

/**
 * Translucent glass surface for list rows (server list, menu items, settings rows).
 * Lighter than [AppCard] so rows stacked together stay calm; a selected row gets a
 * brighter edge and a soft glow. Not a Composable, so it can be chained in any Modifier.
 */
fun Modifier.glassRow(selected: Boolean = false, glow: Color = AppColors.Primary): Modifier {
    val shape = RoundedCornerShape(16.dp)
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    AppColors.GlassBase.copy(alpha = if (selected) 0.72f else 0.52f),
                    AppColors.GlassBase.copy(alpha = if (selected) 0.60f else 0.40f),
                ),
            ),
        )
        .border(
            1.dp,
            Brush.linearGradient(
                if (selected) {
                    listOf(glow.copy(alpha = 0.75f), glow.copy(alpha = 0.30f), glow.copy(alpha = 0.55f))
                } else {
                    listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.03f), glow.copy(alpha = 0.12f))
                },
            ),
            shape,
        )
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

/** The app name written in the neon brand gradient with a soft glow — replaces the wordmark image. */
@Composable
fun NeonWordmark(modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 22.sp) {
    Text(
        text = "FAIROS VPN",
        style = MaterialTheme.typography.titleLarge.copy(
            brush = brandBrush(),
            fontWeight = FontWeight.Black,
            fontSize = fontSize,
            letterSpacing = 1.sp,
            shadow = androidx.compose.ui.graphics.Shadow(
                color = AppColors.Violet.copy(alpha = 0.6f),
                offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                blurRadius = 24f,
            ),
        ),
        modifier = modifier,
    )
}

/** Preset avatar colors (index 1..8); index 0 renders the neutral default. */
val avatarPalette = listOf(
    listOf(AppColors.Blue, AppColors.Violet),
    listOf(AppColors.Magenta, AppColors.Violet),
    listOf(AppColors.Connected, AppColors.Blue),
    listOf(AppColors.Gold, Color(0xFFE0A92E)),
    listOf(AppColors.Danger, AppColors.Magenta),
    listOf(Color(0xFF5BE0FF), AppColors.Blue),
    listOf(AppColors.TextSecondary, AppColors.TextMuted),
    listOf(AppColors.Connecting, AppColors.Danger),
)

/** A built-in preset avatar: a colored gradient disc with the user glyph. preset 0 = neutral default. */
@Composable
fun PresetAvatar(preset: Int, size: Dp, modifier: Modifier = Modifier) {
    val colors = if (preset in 1..avatarPalette.size) avatarPalette[preset - 1] else listOf(AppColors.SurfaceHigh, AppColors.Surface)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(R.drawable.ic_user, tint = Color.White, size = size * 0.52f)
    }
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

private fun Modifier.androidxClick(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

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
