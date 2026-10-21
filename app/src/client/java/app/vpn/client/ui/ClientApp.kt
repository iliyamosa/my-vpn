package app.vpn.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import app.vpn.BuildConfig
import app.vpn.client.MainActivity
import app.vpn.client.data.Analytics
import app.vpn.client.data.ClientStore
import app.vpn.client.data.ServerRepository
import app.vpn.client.vpn.VpnState
import app.vpn.client.vpn.VpnStatus
import app.vpn.client.vpn.XrayVpnService
import app.vpn.common.AdItem
import app.vpn.common.ServerProfile
import app.vpn.ui.AppColors
import app.vpn.ui.SpaceBackground
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Screen { HOME, SERVERS, PRO, SETTINGS }

@Composable
fun ClientApp(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val store = remember { ClientStore(activity) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var ad by remember { mutableStateOf<AdItem?>(null) }
    var pending by remember { mutableStateOf<Deferred<ServerProfile?>?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }
    var noticeDismissedNow by remember { mutableStateOf(false) }

    val status by VpnState.status.collectAsState()
    val config by ServerRepository.config.collectAsState()
    val resumed by activity.resumed.collectAsState()
    val vpnError by VpnState.error.collectAsState()

    // First load + refresh whenever the app comes back to the foreground.
    var lastRefresh by remember { mutableStateOf(0L) }
    LaunchedEffect(resumed) {
        if (resumed && System.currentTimeMillis() - lastRefresh > 120_000) {
            lastRefresh = System.currentTimeMillis()
            ServerRepository.refreshAll(activity)
        }
    }
    // After connecting, refresh again through the tunnel (works even when GitHub is filtered).
    LaunchedEffect(status) {
        if (status == VpnStatus.CONNECTED) {
            delay(4_000)
            ServerRepository.refreshAll(activity)
        }
    }
    LaunchedEffect(vpnError) {
        val e = vpnError
        if (!e.isNullOrBlank()) {
            activity.toast(friendlyError(e))
            VpnState.error.value = null
        }
    }

    fun bypassIran(): Boolean = when (store.bypassIranMode) {
        0 -> false
        1 -> true
        else -> ServerRepository.config.value.bypassIranDefault
    }

    fun launchVpn(job: Deferred<ServerProfile?>) {
        scope.launch {
            val p = job.await()
            if (p == null) {
                VpnState.status.value = VpnStatus.DISCONNECTED
                activity.toast("سروری برای اتصال پیدا نشد. چند لحظه بعد دوباره امتحان کنید.")
                return@launch
            }
            // The VPN service may only be started while the app is in the foreground.
            activity.resumed.first { it }
            val limit = if (ServerRepository.isPro(activity)) 0 else ServerRepository.config.value.free.sessionMinutes
            try {
                XrayVpnService.start(activity, p.rawLink, p.remarks, p.key, bypassIran(), limit)
            } catch (e: Exception) {
                VpnState.status.value = VpnStatus.DISCONNECTED
                activity.toast("شروع اتصال انجام نشد.")
            }
        }
    }

    fun connect() {
        if (ServerRepository.usableServers(activity).isEmpty()) {
            if (ServerRepository.refreshing.value) {
                activity.toast("در حال دریافت لیست سرورها… چند لحظه صبر کنید.")
            } else {
                activity.toast("لیست سرورها خالی است. در حال تلاش دوباره…")
                scope.launch { ServerRepository.refreshAll(activity) }
            }
            return
        }
        activity.ensureVpnPermission { granted ->
            if (!granted) {
                activity.toast("برای اتصال باید اجازه‌ی VPN را بدهید.")
                return@ensureVpnPermission
            }
            val job = scope.async { ServerRepository.resolveServer(activity) }
            val chosenAd = pickAd(activity)
            VpnState.status.value = VpnStatus.CONNECTING
            if (chosenAd != null) {
                pending = job
                ad = chosenAd
                Analytics.track(activity, "ad_view", id = chosenAd.id)
            } else {
                launchVpn(job)
            }
        }
    }

    fun onConnectClick() {
        when (status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> {
                if (ad != null) {
                    ad = null
                    pending?.cancel()
                    pending = null
                    VpnState.status.value = VpnStatus.DISCONNECTED
                } else {
                    XrayVpnService.stop(activity)
                    if (status == VpnStatus.CONNECTING) VpnState.status.value = VpnStatus.DISCONNECTED
                }
            }

            VpnStatus.DISCONNECTING -> {}
            VpnStatus.DISCONNECTED -> connect()
        }
    }

    fun reconnectIfNeeded() {
        if (VpnState.status.value != VpnStatus.CONNECTED) return
        val job = scope.async { ServerRepository.resolveServer(activity) }
        VpnState.status.value = VpnStatus.CONNECTING
        launchVpn(job)
    }

    BackHandler(enabled = screen != Screen.HOME && ad == null) { screen = Screen.HOME }
    BackHandler(enabled = ad != null) {
        ad = null
        pending?.cancel()
        pending = null
        VpnState.status.value = VpnStatus.DISCONNECTED
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        SpaceBackground(connected = status == VpnStatus.CONNECTED)
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                activity = activity,
                onConnectClick = { onConnectClick() },
                onOpenServers = { screen = Screen.SERVERS },
                onOpenPro = { screen = Screen.PRO },
                onOpenSettings = { screen = Screen.SETTINGS },
            )

            Screen.SERVERS -> ServersScreen(
                activity = activity,
                onBack = { screen = Screen.HOME },
                onOpenPro = { screen = Screen.PRO },
                onSelected = {
                    screen = Screen.HOME
                    reconnectIfNeeded()
                },
            )

            Screen.PRO -> ProScreen(
                activity = activity,
                onBack = { screen = Screen.HOME },
                onActivated = { reconnectIfNeeded() },
            )

            Screen.SETTINGS -> SettingsScreen(
                activity = activity,
                onBack = { screen = Screen.HOME },
                onSettingsChanged = { reconnectIfNeeded() },
            )
        }

        val currentAd = ad
        if (currentAd != null) {
            AdScreen(
                activity = activity,
                ad = currentAd,
                onFinished = {
                    Analytics.track(activity, "ad_done", id = currentAd.id)
                    ad = null
                    val job = pending
                    pending = null
                    if (job != null) launchVpn(job) else VpnState.status.value = VpnStatus.DISCONNECTED
                },
            )
        }
        }
    }

    SessionEndDialogs(
        activity = activity,
        onReconnect = { connect() },
        onOpenPro = { screen = Screen.PRO },
    )

    // ---------- dialogs ----------
    val versionCode = BuildConfig.VERSION_CODE
    val update = config.update
    val mustUpdate = update.minVersionCode > versionCode && update.url.isNotBlank()
    val canUpdate = !mustUpdate && update.latestVersionCode > versionCode && update.url.isNotBlank() && !updateDismissed
    if (mustUpdate || canUpdate) {
        AlertDialog(
            onDismissRequest = { if (!mustUpdate) updateDismissed = true },
            title = { Text("به‌روزرسانی", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    update.message + if (mustUpdate) "\n\nبرای ادامه‌ی استفاده باید برنامه را به‌روز کنید." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { activity.openLink(update.url) }) { Text("دانلود نسخه‌ی جدید") }
            },
            dismissButton = if (mustUpdate) null else {
                { TextButton(onClick = { updateDismissed = true }) { Text("بعداً") } }
            },
            containerColor = AppColors.SurfaceHigh,
        )
    } else {
        val notice = config.notice
        val showNotice = notice.enabled && notice.id.isNotBlank() && notice.id != store.dismissedNoticeId &&
            !noticeDismissedNow && (notice.title.isNotBlank() || notice.message.isNotBlank())
        if (showNotice) {
            AlertDialog(
                onDismissRequest = {
                    store.dismissedNoticeId = notice.id
                    noticeDismissedNow = true
                },
                title = if (notice.title.isNotBlank()) {
                    { Text(notice.title, style = MaterialTheme.typography.titleMedium) }
                } else null,
                text = { Text(notice.message, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    if (notice.link.isNotBlank()) {
                        TextButton(onClick = {
                            store.dismissedNoticeId = notice.id
                            noticeDismissedNow = true
                            activity.openLink(notice.link)
                        }) { Text(notice.buttonText.ifBlank { "مشاهده" }) }
                    } else {
                        TextButton(onClick = {
                            store.dismissedNoticeId = notice.id
                            noticeDismissedNow = true
                        }) { Text("باشه") }
                    }
                },
                dismissButton = if (notice.link.isNotBlank()) {
                    {
                        TextButton(onClick = {
                            store.dismissedNoticeId = notice.id
                            noticeDismissedNow = true
                        }) { Text("بستن") }
                    }
                } else null,
                containerColor = AppColors.SurfaceHigh,
            )
        }
    }
}

/** Chooses an ad to show before connecting, or null when ads are off for this user. */
private fun pickAd(activity: MainActivity): AdItem? {
    val cfg = ServerRepository.config.value.ads
    if (!cfg.enabled) return null
    if (ServerRepository.isPro(activity) && !cfg.showForPro) return null
    val items = cfg.activeItems()
    if (items.isEmpty()) return null
    val total = items.sumOf { it.weight }
    var r = Random.nextInt(total)
    for (item in items) {
        r -= item.weight
        if (r < 0) return item
    }
    return items.last()
}

private fun friendlyError(e: String): String {
    val lower = e.lowercase()
    return when {
        lower.contains("prohibited") -> "این سرور بدون رمزنگاری (TLS) است و پشتیبانی نمی‌شود."
        lower.contains("config") || lower.contains("failed to build") -> "تنظیمات این سرور معتبر نیست. سرور دیگری را امتحان کنید."
        e.any { it in 'ا'..'ی' } -> e
        else -> "اتصال برقرار نشد. سرور دیگری را امتحان کنید."
    }
}
