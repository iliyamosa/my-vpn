package app.vpn.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.AccountManager
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.Pill
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    activity: MainActivity,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenPoints: () -> Unit,
    onOpenPro: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val account by AccountManager.account.collectAsState()
    val avatar by AccountManager.avatar.collectAsState()
    val isAdmin by AccountManager.isAdmin.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("پروفایل", onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Identity header
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (account?.isPro == true) AppColors.Gold else AppColors.Violet, CircleShape)
                            .background(AppColors.SurfaceHigh)
                            .clickable { onOpenInfo() },
                        contentAlignment = Alignment.Center,
                    ) {
                        val a = avatar
                        if (a != null) {
                            Image(
                                bitmap = a.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(CircleShape),
                            )
                        } else {
                            AppIcon(R.drawable.ic_user, tint = AppColors.TextSecondary, size = 30.dp)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                account?.displayName ?: "کاربر",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (account?.isPro == true) {
                                Spacer(Modifier.width(8.dp))
                                Pill("پرو", AppColors.Gold)
                            }
                        }
                        val uname = account?.username
                        if (!uname.isNullOrBlank()) {
                            Text("@$uname", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                        Text("${account?.points ?: 0} امتیاز".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.Primary)
                    }
                }
            }

            MenuCard {
                MenuRow(R.drawable.ic_user, "اطلاعات حساب", "نام نمایشی، تصویر و رمز عبور", onOpenInfo)
                MenuRow(R.drawable.ic_crown, "اشتراک پرو", "خرید، تمدید و وضعیت اشتراک", onOpenPro, tint = AppColors.Gold)
                MenuRow(R.drawable.ic_gift, "امتیازها و ماموریت‌ها", "با انجام ماموریت‌ها پرو بگیرید", onOpenPoints)
                MenuRow(R.drawable.ic_apps, "برنامه‌ها و تونل انتخابی", "انتخاب اینکه کدام برنامه‌ها از VPN استفاده کنند", onOpenApps)
                MenuRow(R.drawable.ic_bell, "اعلان‌ها", "پیام‌های مدیریت", onOpenNotices)
                MenuRow(R.drawable.ic_tune, "تنظیمات", "گزینه‌های اتصال و برنامه", onOpenSettings)
                MenuRow(R.drawable.ic_refresh, "عیب‌یابی اتصال", "بررسی وضعیت سرور و شبکه", onOpenDiagnostics, last = !isAdmin)
                if (isAdmin) {
                    MenuRow(R.drawable.ic_tune, "پنل مدیریت", "مدیریت کاربران، سرورها و پرداخت", onOpenAdmin, tint = AppColors.Magenta, last = true)
                }
            }

            AppCard(modifier = Modifier.clickable { confirmLogout = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_logout, tint = AppColors.Danger, size = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("خروج از حساب", color = AppColors.Danger, style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("خروج از حساب", style = MaterialTheme.typography.titleMedium) },
            text = { Text("از حساب کاربری خارج می‌شوید؟ برای استفاده دوباره باید وارد شوید.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch { AccountManager.logout(activity) }
                }) { Text("خروج", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("انصراف") } },
            containerColor = AppColors.SurfaceHigh,
        )
    }
}

@Composable
fun MenuCard(content: @Composable () -> Unit) {
    AppCard { content() }
}

@Composable
fun MenuRow(
    icon: Int,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    tint: Color = AppColors.Primary,
    last: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(tint.copy(alpha = 0.16f))
                .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, tint = tint, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
            }
        }
        AppIcon(R.drawable.ic_back, tint = AppColors.TextMuted, size = 18.dp, modifier = Modifier.scale(-1f, 1f))
    }
    if (!last) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.4f)))
    }
}
