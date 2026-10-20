package app.vpn.client.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vpn.client.MainActivity
import app.vpn.client.data.AdCache
import app.vpn.common.AdItem
import app.vpn.common.Http
import app.vpn.common.faDigits
import app.vpn.ui.AppColors
import app.vpn.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Full screen ad shown after tapping "connect"; the VPN connects when the countdown ends. */
@Composable
fun AdScreen(activity: MainActivity, ad: AdItem, onFinished: () -> Unit) {
    var remaining by remember(ad) { mutableIntStateOf(ad.seconds.coerceAtLeast(0)) }
    var image by remember(ad) { mutableStateOf<ImageBitmap?>(null) }
    var finished by remember(ad) { mutableStateOf(false) }

    LaunchedEffect(ad) {
        if (ad.imageUrl.isNotBlank()) image = loadAdImage(activity, ad.imageUrl)
    }
    LaunchedEffect(ad) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        activity.resumed.first { it }
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill("تبلیغ", AppColors.Gold)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AppColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (remaining > 0) {
                        Text("$remaining".faDigits(), fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Primary)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            val img = image
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(enabled = ad.link.isNotBlank()) { activity.openLink(ad.link) },
                )
                Spacer(Modifier.height(20.dp))
            } else if (ad.imageUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppColors.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AppColors.Primary)
                }
                Spacer(Modifier.height(20.dp))
            }

            if (ad.title.isNotBlank()) {
                Text(ad.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
            }
            if (ad.text.isNotBlank()) {
                Text(
                    ad.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
            }
            if (ad.link.isNotBlank()) {
                Button(
                    onClick = { activity.openLink(ad.link) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(ad.buttonText.ifBlank { "مشاهده" }) }
            }

            Spacer(Modifier.height(28.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    if (remaining > 0) "اتصال تا $remaining ثانیه‌ی دیگر…".faDigits() else "در حال اتصال…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextMuted,
                )
            }
        }
    }
}

private suspend fun loadAdImage(context: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val file = AdCache.fileFor(context, url)
        if (!file.exists() || file.length() == 0L) {
            Http.download(url, file, timeoutMs = 5_000, viaTunnel = Http.tunnelSocksPort > 0)
        }
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 2560) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
    } catch (e: Throwable) {
        null
    }
}
