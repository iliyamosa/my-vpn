package app.vpn.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vpn.BuildConfig
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.ClientStore
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
    onOpenPro: () -> Unit,
    onOpenSettings: () -> Unit,
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
    val proInfo by ServerRepository.proInfo.collectAsState()
    val refreshing by ServerRepository.refreshing.collectAsState()
    val lastError by ServerRepository.lastError.collectAsState()
    val delays by ServerRepository.delays.collectAsState()

    val isPro = remember(proServers, proInfo) { ServerRepository.isPro(activity) }
    val proProblem = remember(proServers, proInfo) { ServerRepository.proProblem(activity) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_shield, tint = AppColors.Primary, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isPro) Pill("پرو", AppColors.Gold)
            if (config.support.telegram.isNotBlank()) {
                IconButton(onClick = { activity.openLink(config.support.telegram) }) {
                    AppIcon(R.drawable.ic_send, tint = AppColors.TextSecondary)
                }
            }
            IconButton(onClick = onOpenSettings) {
                AppIcon(R.drawable.ic_tune, tint = AppColors.TextSecondary)
            }
        }

        Spacer(Modifier.height(28.dp))

        ConnectButton(status = status, onClick = onConnectClick)

        Spacer(Modifier.height(18.dp))

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
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(connectedAt) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }
            val secs = ((now - connectedAt) / 1000).coerceAtLeast(0)
            val text = String.format(java.util.Locale.US, "%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
            Text(text.faDigits(), style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("↓ ${formatBytes(down)}/s", color = AppColors.Connected, style = MaterialTheme.typography.bodyMedium)
                Text("↑ ${formatBytes(up)}/s", color = AppColors.Primary, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                if (status == VpnStatus.DISCONNECTED) "برای اتصال، دکمه را بزنید" else " ",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextMuted,
            )
        }

        Spacer(Modifier.height(26.dp))

        // Server card
        val store = remember { ClientStore(activity) }
        val selectedKey = store.selectedKey
        val all = remember(freeServers, proServers, isPro) { ServerRepository.usableServers(activity) }
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

        if (!lastError.isNullOrBlank() && all.isEmpty() && !refreshing) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { scope.launch { ServerRepository.refreshAll(activity) } }) {
                AppIcon(R.drawable.ic_refresh, tint = AppColors.Primary, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("تلاش دوباره", color = AppColors.Primary)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Pro card
        when {
            isPro -> ProStatusCard(info = proInfo, onClick = onOpenPro)
            proProblem != null -> AppCard(modifier = Modifier.clickable { onOpenPro() }) {
                Text(proProblem, color = AppColors.Danger, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("برای تمدید یا وارد کردن لینک جدید ضربه بزنید.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }

            config.pro.enabled -> AppCard(color = Color(0xFF2A2210), modifier = Modifier.clickable { onOpenPro() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_star, tint = AppColors.Gold, size = 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(config.pro.title, style = MaterialTheme.typography.titleSmall, color = AppColors.Gold)
                        Text(config.pro.description, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                    Button(
                        onClick = onOpenPro,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Gold, contentColor = Color(0xFF1F1500)),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("ارتقا") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "نسخه ${BuildConfig.VERSION_NAME}".faDigits(),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConnectButton(status: VpnStatus, onClick: () -> Unit) {
    val target = when (status) {
        VpnStatus.CONNECTED -> AppColors.Connected
        VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> AppColors.Connecting
        VpnStatus.DISCONNECTED -> AppColors.Primary
    }
    val color by animateColorAsState(targetValue = target, label = "btnColor")
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(if (busy) pulse else 1f)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.10f)),
        )
        Box(
            modifier = Modifier
                .size(170.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.18f)),
        )
        Box(
            modifier = Modifier
                .size(138.dp)
                .clip(CircleShape)
                .background(color)
                .border(3.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                .clickable(enabled = status != VpnStatus.DISCONNECTING) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(R.drawable.ic_power, tint = Color.White, size = 54.dp)
                Text(
                    when (status) {
                        VpnStatus.CONNECTED -> "قطع"
                        VpnStatus.CONNECTING -> "لغو"
                        VpnStatus.DISCONNECTING -> "…"
                        VpnStatus.DISCONNECTED -> "اتصال"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
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
fun ProStatusCard(info: app.vpn.common.SubscriptionInfo?, onClick: () -> Unit) {
    AppCard(color = Color(0xFF2A2210), modifier = Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_star, tint = AppColors.Gold, size = 24.dp)
            Spacer(Modifier.width(10.dp))
            Text("اشتراک پرو فعال است", style = MaterialTheme.typography.titleSmall, color = AppColors.Gold, modifier = Modifier.weight(1f))
            if (info != null && info.daysLeft() >= 0) {
                Text("${info.daysLeft()} روز مانده".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
        if (info != null && info.total > 0) {
            Spacer(Modifier.height(10.dp))
            val fraction = (info.used.toFloat() / info.total.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = if (fraction > 0.9f) AppColors.Danger else AppColors.Gold,
                trackColor = Color.White.copy(alpha = 0.08f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(info.used)} از ${formatBytes(info.total)} مصرف شده".faDigits(),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }
    }
}
