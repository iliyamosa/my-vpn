package app.vpn.client.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vpn.BuildConfig
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.AccountManager
import app.vpn.client.data.ClientStore
import app.vpn.client.data.NoticesStore
import app.vpn.client.data.ServerRepository
import app.vpn.client.vpn.VpnState
import app.vpn.client.vpn.VpnStatus
import app.vpn.common.faDigits
import app.vpn.common.formatBytes
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.Pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    activity: MainActivity,
    onConnectClick: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenPro: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by VpnState.status.collectAsState()
    val connectedAt by VpnState.connectedAt.collectAsState()
    val serverName by VpnState.serverName.collectAsState()
    val up by VpnState.upSpeed.collectAsState()
    val down by VpnState.downSpeed.collectAsState()
    val config by ServerRepository.config.collectAsState()
    val freeServers by ServerRepository.freeServers.collectAsState()
    val proServers by ServerRepository.proServers.collectAsState()
    val accountIsPro by ServerRepository.accountIsPro.collectAsState()
    val refreshing by ServerRepository.refreshing.collectAsState()
    val lastError by ServerRepository.lastError.collectAsState()
    val freeError by ServerRepository.lastFreeError.collectAsState()
    val delays by ServerRepository.delays.collectAsState()

    val avatar by AccountManager.avatar.collectAsState()
    val account by AccountManager.account.collectAsState()
    val unread by NoticesStore.unread.collectAsState()

    val deadline by VpnState.sessionDeadline.collectAsState()
    val ping by VpnState.pingMs.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status == VpnStatus.CONNECTED) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HomeTopBar(
            avatar = avatar,
            unreadNotices = unread,
            isPro = accountIsPro,
            onOpenNotices = onOpenNotices,
            onOpenProfile = onOpenProfile,
        )

        Spacer(Modifier.height(4.dp))
        val hello = account?.displayName?.takeIf { it.isNotBlank() }
        if (hello != null) {
            Text(
                "سلام، $hello",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        // The branded connect element.
        ConnectButton(status = status, onClick = onConnectClick, size = 220.dp)

        Spacer(Modifier.height(16.dp))

        val statusText = when (status) {
            VpnStatus.DISCONNECTED -> "متصل نیستید"
            VpnStatus.CONNECTING -> "در حال اتصال…"
            VpnStatus.CONNECTED -> "متصل هستید"
            VpnStatus.DISCONNECTING -> "در حال قطع اتصال…"
        }
        val statusColor = when (status) {
            VpnStatus.CONNECTED -> AppColors.Connected
            VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> AppColors.Connecting
            VpnStatus.DISCONNECTED -> AppColors.TextSecondary
        }
        Text(statusText, style = MaterialTheme.typography.titleMedium, color = statusColor)

        if (status == VpnStatus.CONNECTED && connectedAt > 0) {
            val secs = ((now - connectedAt) / 1000).coerceAtLeast(0)
            val text = String.format(java.util.Locale.US, "%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
            Text(text.faDigits(), style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↓ ${formatBytes(down)}/s", color = AppColors.Connected, style = MaterialTheme.typography.bodyMedium)
                Text("↑ ${formatBytes(up)}/s", color = AppColors.Primary, style = MaterialTheme.typography.bodyMedium)
                when {
                    ping >= 0 -> Text("پینگ $ping ms".faDigits(), color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    ping == VpnState.PING_TESTING -> Text("در حال بررسی…", color = AppColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    else -> {}
                }
            }
            if (ping == VpnState.PING_FAILED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "این سرور پاسخ نمی‌دهد. برای انتخاب سرور دیگر ضربه بزنید.",
                    color = AppColors.Danger,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { onOpenServers() },
                )
            }
            if (deadline > 0) {
                val left = ((deadline - now) / 1000).coerceAtLeast(0)
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AppColors.Gold.copy(alpha = 0.14f))
                        .clickable { onOpenPro() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        String.format(java.util.Locale.US, "زمان رایگان باقی‌مانده: %02d:%02d", left / 60, left % 60).faDigits() +
                            "  •  بدون محدودیت با پرو",
                        color = AppColors.Gold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                if (status == VpnStatus.DISCONNECTED) "برای اتصال، دکمه را بزنید" else " ",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextMuted,
            )
        }

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Server card
            val store = remember { ClientStore(activity) }
            val selectedKey = store.selectedKey
            val all = remember(freeServers, proServers, accountIsPro) { ServerRepository.usableServers(activity) }
            val selected = all.firstOrNull { it.key == selectedKey }
            val displayName = when {
                status == VpnStatus.CONNECTED && serverName.isNotBlank() -> serverName
                selected != null -> selected.remarks
                else -> "خودکار (سریع‌ترین سرور)"
            }
            AppCard(modifier = Modifier.clickable { onOpenServers() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(if (selected == null) R.drawable.ic_bolt else R.drawable.ic_servers, tint = AppColors.Primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("سرور", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                        Text(displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val d = selected?.let { delays[it.key] }
                    if (d != null) DelayPill(d)
                    Spacer(Modifier.width(6.dp))
                    AppIcon(R.drawable.ic_back, tint = AppColors.TextMuted, size = 20.dp, modifier = Modifier.scale(-1f, 1f))
                }
                if (all.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (refreshing) "در حال دریافت لیست سرورها…" else "هنوز سروری دریافت نشده است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Connecting,
                    )
                }
            }

            if (all.isEmpty() && !refreshing) {
                val reason = freeError ?: lastError
                if (!reason.isNullOrBlank()) {
                    Text(reason, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { ServerRepository.refreshAll(activity) } }) {
                        AppIcon(R.drawable.ic_refresh, tint = AppColors.Primary, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("تلاش دوباره", color = AppColors.Primary)
                    }
                }
            }

            // Pro card
            if (accountIsPro) {
                ProStatusCard(account = account, onClick = onOpenPro)
            } else {
                AppCard(color = Color(0xFF2A2210), glow = AppColors.Gold, modifier = Modifier.clickable { onOpenPro() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_crown, tint = AppColors.Gold, size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(config.pro.title.ifBlank { "اشتراک پرو" }, style = MaterialTheme.typography.titleSmall, color = AppColors.Gold)
                            Text(
                                config.pro.description.ifBlank { "سرورهای پرسرعت اختصاصی، بدون محدودیت زمان و تبلیغات" },
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary,
                            )
                        }
                        Button(
                            onClick = onOpenPro,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Gold, contentColor = Color(0xFF1F1500)),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("ارتقا") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "نسخه ${BuildConfig.VERSION_NAME}".faDigits(),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DelayPill(ms: Long) {
    when {
        ms == ServerRepository.DELAY_TESTING -> Pill("…", AppColors.TextSecondary)
        ms < 0 -> Pill("خطا", AppColors.Danger)
        ms < 400 -> Pill("$ms ms".faDigits(), AppColors.Connected)
        ms < 1000 -> Pill("$ms ms".faDigits(), AppColors.Connecting)
        else -> Pill("$ms ms".faDigits(), AppColors.Danger)
    }
}

@Composable
fun ProStatusCard(account: app.vpn.common.Account?, onClick: () -> Unit) {
    AppCard(color = Color(0xFF2A2210), glow = AppColors.Gold, modifier = Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_crown, tint = AppColors.Gold, size = 24.dp)
            Spacer(Modifier.width(10.dp))
            Text("اشتراک پرو فعال است", style = MaterialTheme.typography.titleSmall, color = AppColors.Gold, modifier = Modifier.weight(1f))
            val daysLeft = account?.let { ((it.proUntil - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) }
            if (daysLeft != null) {
                Text("$daysLeft روز مانده".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
    }
}
