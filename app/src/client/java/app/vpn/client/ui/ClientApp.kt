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
import app.vpn.client.data.AccountManager
import app.vpn.client.data.Analytics
import app.vpn.client.data.ClientStore
import app.vpn.client.data.NoticesStore
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

enum class Screen { HOME, SERVERS, PRO, PROFILE, INFO, APPS, POINTS, NOTICES, SETTINGS, DIAG, ADMIN, FAQ }

@Composable
fun ClientApp(activity: MainActivity) {
    val loggedIn by AccountManager.loggedIn.collectAsState()
    val isAdmin by AccountManager.isAdmin.collectAsState()

    when {
        // A plain user (or an admin who is also signed in as a user) gets the full app.
        loggedIn -> MainApp(activity)
        // An admin who signed in only as admin goes straight to the management panel.
        isAdmin -> Box(Modifier.fillMaxSize().background(AppColors.Background)) {
            SpaceBackground(connected = false)
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                AdminPanel(activity, onExit = { AccountManager.adminLogout(activity) })
            }
        }
        // Not signed in: the mandatory login / register gate.
        else -> AuthScreen(activity)
    }
}

@Composable
private fun MainApp(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val store = remember { ClientStore(activity) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var ad by remember { mutableStateOf<AdItem?>(null) }
    var pending by remember { mutableStateOf<Deferred<ServerProfile?>?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }

    val status by VpnState.status.collectAsState()
    val config by ServerRepository.config.collectAsState()
    val resumed by activity.resumed.collectAsState()
    val vpnError by VpnState.error.collectAsState()
    val isAdmin by AccountManager.isAdmin.collectAsState()

    // Load account details + servers + notices on first entry and whenever we return to the foreground.
    var lastRefresh by remember { mutableStateOf(0L) }
    LaunchedEffect(resumed) {
        if (resumed) {
            // Always refresh the account on foreground so pro status is instant (it is a cheap call).
            scope.launch { AccountManager.refreshMe(activity) }
            if (System.currentTimeMillis() - lastRefresh > 120_000) {
                lastRefresh = System.currentTimeMillis()
                scope.launch { NoticesStore.refresh(activity) }
                ServerRepository.refreshAll(activity)
            }
        }
    }
    // After connecting, refresh again through the tunnel (works even when GitHub is filtered).
    LaunchedEffect(status) {
        if (status == VpnStatus.CONNECTED) {
            delay(4_000)
            ServerRepository.refreshAll(activity)
            AccountManager.refreshMe(activity)
            NoticesStore.refresh(activity)
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

    BackHandler(enabled = screen != Screen.HOME && ad == null) {
        screen = if (screen == Screen.INFO || screen == Screen.APPS || screen == Screen.POINTS || screen == Screen.FAQ) Screen.PROFILE else Screen.HOME
    }
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
                    onOpenProfile = { screen = Screen.PROFILE },
                    onOpenNotices = { screen = Screen.NOTICES },
                    onOpenPro = { screen = Screen.PRO },
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

                Screen.PROFILE -> ProfileScreen(
                    activity = activity,
                    onBack = { screen = Screen.HOME },
                    onOpenInfo = { screen = Screen.INFO },
                    onOpenApps = { screen = Screen.APPS },
                    onOpenPoints = { screen = Screen.POINTS },
                    onOpenPro = { screen = Screen.PRO },
                    onOpenNotices = { screen = Screen.NOTICES },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenDiagnostics = { screen = Screen.DIAG },
                    onOpenAdmin = { screen = Screen.ADMIN },
                    onOpenFaq = { screen = Screen.FAQ },
                )

                Screen.FAQ -> FaqScreen(onBack = { screen = Screen.PROFILE })
                Screen.INFO -> InfoScreen(activity = activity, onBack = { screen = Screen.PROFILE })
                Screen.APPS -> AppsScreen(activity = activity, onBack = { screen = Screen.PROFILE }, onChanged = { reconnectIfNeeded() })
                Screen.POINTS -> PointsScreen(activity = activity, onBack = { screen = Screen.PROFILE }, onOpenPro = { screen = Screen.PRO })
                Screen.NOTICES -> NoticesScreen(activity = activity, onBack = { screen = Screen.HOME })

                Screen.SETTINGS -> SettingsScreen(
                    activity = activity,
                    onBack = { screen = Screen.PROFILE },
                    onSettingsChanged = { reconnectIfNeeded() },
                    onOpenDiagnostics = { screen = Screen.DIAG },
                )

                Screen.DIAG -> DiagnosticsScreen(activity = activity, onBack = { screen = Screen.HOME })

                Screen.ADMIN -> AdminPanel(activity = activity, onExit = { screen = Screen.PROFILE })
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

    // One-time welcome for a brand-new user (free-pro gift + optional invite code).
    val welcome by AccountManager.pendingWelcome.collectAsState()
    if (welcome != null) {
        WelcomeOverlay(activity, welcomeDays = welcome ?: 0, onDone = { AccountManager.pendingWelcome.value = null })
    }

    SessionEndDialogs(
        activity = activity,
        onReconnect = { connect() },
        onOpenPro = { screen = Screen.PRO },
    )

    // ---------- forced-update dialog ----------
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
