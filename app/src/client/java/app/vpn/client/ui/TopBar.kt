package app.vpn.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import app.vpn.R
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.NeonWordmark
import app.vpn.ui.PresetAvatar

/**
 * Home top bar: the FAIROS wordmark on the left, the notices bell and the profile avatar on the right.
 * Laid out left-to-right explicitly so the sides stay fixed regardless of the app's RTL direction.
 */
@Composable
fun HomeTopBar(
    avatar: Bitmap?,
    unreadNotices: Boolean,
    isPro: Boolean,
    avatarPreset: Int = 0,
    onOpenNotices: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(6.dp))
            NeonWordmark(fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            // Bell with an unread dot
            Box {
                IconButton(onClick = onOpenNotices) {
                    AppIcon(R.drawable.ic_bell, tint = AppColors.TextSecondary, size = 24.dp)
                }
                if (unreadNotices) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-8).dp, y = 8.dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(AppColors.Magenta),
                    )
                }
            }
            ProfileButton(avatar = avatar, isPro = isPro, avatarPreset = avatarPreset, onClick = onOpenProfile)
        }
    }
}

@Composable
fun ProfileButton(avatar: Bitmap?, isPro: Boolean, avatarPreset: Int = 0, onClick: () -> Unit) {
    val ring = if (isPro) AppColors.Gold else AppColors.Violet
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(2.dp, ring, CircleShape)
            .background(AppColors.SurfaceHigh)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar.asImageBitmap(),
                contentDescription = "پروفایل",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(CircleShape),
            )
        } else if (avatarPreset > 0) {
            PresetAvatar(preset = avatarPreset, size = 34.dp)
        } else {
            AppIcon(R.drawable.ic_user, tint = AppColors.TextSecondary, size = 22.dp)
        }
    }
}

/** A simple back + title header used by the inner screens. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { AppIcon(R.drawable.ic_back) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}
