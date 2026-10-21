package app.vpn.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.Analytics
import app.vpn.client.data.ClientStore
import app.vpn.client.data.Realtime
import app.vpn.client.data.ServerRepository
import app.vpn.common.Http
import app.vpn.common.LinkParser
import app.vpn.common.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket

class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.vpn.action.START"
        const val ACTION_STOP = "app.vpn.action.STOP"
        const val EXTRA_LINK = "link"
        const val EXTRA_NAME = "name"
        const val EXTRA_KEY = "key"
        const val EXTRA_BYPASS = "bypass"
        const val EXTRA_LIMIT_MIN = "limit_min"

        private const val CHANNEL_ID = "vpn_status"
        private const val CHANNEL_ALERT = "vpn_alerts"
        private const val NOTIFICATION_ID = 7001
        private const val NOTIFICATION_ALERT_ID = 7002

        /** limitMinutes > 0 only for free users. */
        fun start(context: Context, link: String, name: String, key: String, bypassIran: Boolean, limitMinutes: Int) {
            val intent = Intent(context, XrayVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LINK, link)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_BYPASS, bypassIran)
                .putExtra(EXTRA_LIMIT_MIN, limitMinutes)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (VpnState.status.value == VpnStatus.DISCONNECTED) return
            try {
                context.startService(Intent(context, XrayVpnService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                VpnState.status.value = VpnStatus.DISCONNECTED
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var startJob: Job? = null
    private var limitJob: Job? = null
    private var pingJob: Job? = null
    private var currentName = ""
    private var sessionStart = 0L
    private var serverStart = 0L
    private var limitMinutes = 0

    override fun onCreate() {
        super.onCreate()
        ServerRepository.loadCache(this)
        Realtime.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startForegroundCompat(buildNotification("در حال قطع اتصال…", null))
            scope.launch { stopVpn(EndReason.USER) }
            return START_NOT_STICKY
        }

        val store = ClientStore(this)
        val fromApp = intent?.action == ACTION_START && intent.hasExtra(EXTRA_LINK)
        startForegroundCompat(buildNotification("در حال اتصال…", null))

        if (!fromApp) {
            // Started by Android (always-on VPN / restart). Free users must open the app (and see the ad).
            if (store.lastLimitMinutes > 0 || store.lastLink.isBlank()) {
                showAlert("برای اتصال، برنامه را باز کنید", "اتصال رایگان فقط از داخل برنامه برقرار می‌شود.")
                scope.launch { stopVpn(EndReason.SYSTEM) }
                return START_NOT_STICKY
            }
        }

        val link = if (fromApp) intent!!.getStringExtra(EXTRA_LINK).orEmpty() else store.lastLink
        val name = if (fromApp) intent!!.getStringExtra(EXTRA_NAME).orEmpty() else store.lastName
        val key = if (fromApp) intent!!.getStringExtra(EXTRA_KEY).orEmpty() else ""
        val bypass = if (fromApp) intent!!.getBooleanExtra(EXTRA_BYPASS, true) else store.lastBypass
        val limit = if (fromApp) intent!!.getIntExtra(EXTRA_LIMIT_MIN, 0) else 0

        if (link.isBlank()) {
            VpnState.error.value = "سروری انتخاب نشده است."
            scope.launch { stopVpn(EndReason.ERROR) }
            return START_NOT_STICKY
        }
        store.lastLink = link
        store.lastName = name
        store.lastBypass = bypass
        store.lastLimitMinutes = limit

        startJob?.cancel()
        startJob = scope.launch { startVpn(link, name, key, bypass, limit) }
        return START_STICKY
    }

    private fun startVpn(link: String, name: String, key: String, bypass: Boolean, limit: Int) {
        // A running core means the user is switching servers inside the same session.
        val switching = sessionStart > 0 && CoreManager.isRunning()
        VpnState.error.value = null
        VpnState.status.value = VpnStatus.CONNECTING
        VpnState.serverName.value = name
        VpnState.serverKey.value = key
        val previousName = currentName
        currentName = name
        try {
            statsJob?.cancel()
            pingJob?.cancel()
            if (switching && previousName.isNotBlank()) recordSession(previousName)
            CoreManager.stop()
            closeTun()

            val profile = LinkParser.parse(link) ?: throw IllegalArgumentException("لینک سرور نامعتبر است.")
            val socksPort = freePort()

            val builder = Builder()
                .setSession(name.ifBlank { getString(R.string.app_name) })
                .setMtu(XrayConfigBuilder.MTU)
                .addAddress("10.10.14.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
            try {
                builder.addAddress("fd66:6666:6666::1", 126)
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                // device without IPv6 support
            }
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                // should not happen
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            val pfd = builder.establish() ?: throw IllegalStateException("اجازه‌ی VPN داده نشده است.")
            tun = pfd

            try {
                CoreManager.start(this, XrayConfigBuilder.buildVpnConfig(profile, socksPort, bypass, useGeoip = true), pfd.fd)
            } catch (e: Exception) {
                CoreManager.stop()
                CoreManager.start(this, XrayConfigBuilder.buildVpnConfig(profile, socksPort, bypass, useGeoip = false), pfd.fd)
            }

            Http.tunnelSocksPort = socksPort
            val now = System.currentTimeMillis()
            // Switching servers keeps the same free session (the clock does not restart).
            if (!switching || limit <= 0 || limitMinutes <= 0) sessionStart = now
            if (VpnState.connectedAt.value == 0L || !switching) VpnState.connectedAt.value = now
            limitMinutes = limit
            serverStart = now
            VpnState.resetTraffic()
            VpnState.status.value = VpnStatus.CONNECTED
            notifyStatus("متصل به $name", null)
            startStats()
            startLimitTimer()
            checkServer(name)
        } catch (e: Exception) {
            VpnState.error.value = e.message ?: "اتصال برقرار نشد."
            Analytics.track(this, "conn_fail", server = name)
            stopVpn(EndReason.ERROR)
        }
    }

    /** The core starts even if the server is down, so test a real request through it. */
    private fun checkServer(name: String) {
        VpnState.pingMs.value = VpnState.PING_TESTING
        pingJob = scope.launch {
            val url = ServerRepository.config.value.testUrl
            var d = CoreManager.measureCurrentDelay(url)
            if (d < 0) {
                delay(1500)
                d = CoreManager.measureCurrentDelay(url)
            }
            if (!isActive) return@launch
            VpnState.pingMs.value = if (d >= 0) d else VpnState.PING_FAILED
            Analytics.track(this@XrayVpnService, if (d >= 0) "conn_ok" else "conn_fail", server = name)
            Analytics.flush(this@XrayVpnService)
        }
    }

    private fun startLimitTimer() {
        limitJob?.cancel()
        if (limitMinutes <= 0) {
            VpnState.sessionDeadline.value = 0
            return
        }
        val deadline = sessionStart + limitMinutes * 60_000L
        VpnState.sessionDeadline.value = deadline
        limitJob = scope.launch {
            val wait = deadline - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            Analytics.track(this@XrayVpnService, "free_timeout")
            showAlert(
                "زمان اتصال رایگان تمام شد",
                "برای اتصال دوباره، برنامه را باز کنید و دکمه‌ی اتصال را بزنید.",
            )
            stopVpn(EndReason.FREE_LIMIT)
        }
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                var up = 0L
                var down = 0L
                CoreManager.queryStats().split(";").forEach { item ->
                    val parts = item.split(",")
                    if (parts.size == 3 && parts[0] == "proxy") {
                        val v = parts[2].toLongOrNull() ?: 0
                        if (parts[1] == "uplink") up += v else if (parts[1] == "downlink") down += v
                    }
                }
                VpnState.upSpeed.value = up
                VpnState.downSpeed.value = down
                VpnState.totalUp.value += up
                VpnState.totalDown.value += down
                tick++
                if (tick % 3 == 0 && VpnState.status.value == VpnStatus.CONNECTED) {
                    val left = VpnState.sessionDeadline.value.let { if (it > 0) (it - System.currentTimeMillis()) / 60_000 else -1 }
                    val extra = if (left >= 0) "   •   ${left + 1} دقیقه مانده" else ""
                    notifyStatus("متصل به $currentName", "↓ ${formatBytes(down)}/s   ↑ ${formatBytes(up)}/s$extra")
                }
            }
        }
    }

    /** Minutes spent on one server (for per-server stats). */
    private fun recordSession(name: String) {
        if (serverStart <= 0) return
        val minutes = ((System.currentTimeMillis() - serverStart) / 60_000).toInt()
        serverStart = 0
        if (minutes > 0) Analytics.track(this, "session", server = name, extra = mapOf("min" to minutes))
    }

    private fun stopVpn(reason: EndReason) {
        val wasConnected = VpnState.status.value == VpnStatus.CONNECTED || VpnState.connectedAt.value > 0
        val minutes = if (VpnState.connectedAt.value > 0) ((System.currentTimeMillis() - VpnState.connectedAt.value) / 60_000).toInt() else 0
        if (wasConnected && currentName.isNotBlank()) {
            recordSession(currentName)
            VpnState.lastSessionEnd.value = SessionEnd(currentName, minutes, reason)
        }
        VpnState.status.value = VpnStatus.DISCONNECTING
        statsJob?.cancel()
        limitJob?.cancel()
        pingJob?.cancel()
        Http.tunnelSocksPort = 0
        CoreManager.stop()
        closeTun()
        sessionStart = 0
        limitMinutes = 0
        VpnState.connectedAt.value = 0
        VpnState.sessionDeadline.value = 0
        VpnState.pingMs.value = VpnState.PING_TESTING
        VpnState.resetTraffic()
        VpnState.status.value = VpnStatus.DISCONNECTED
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        scope.launch { Analytics.flush(this@XrayVpnService) }
    }

    private fun closeTun() {
        try {
            tun?.close()
        } catch (e: Exception) {
            // ignore
        }
        tun = null
    }

    override fun onRevoke() {
        scope.launch { stopVpn(EndReason.SYSTEM) }
    }

    override fun onDestroy() {
        statsJob?.cancel()
        limitJob?.cancel()
        pingJob?.cancel()
        if (CoreManager.isRunning()) CoreManager.stop()
        closeTun()
        Http.tunnelSocksPort = 0
        if (VpnState.status.value != VpnStatus.DISCONNECTED) {
            VpnState.status.value = VpnStatus.DISCONNECTED
            VpnState.connectedAt.value = 0
            VpnState.sessionDeadline.value = 0
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun freePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (e: Exception) {
        10808
    }

    // ---------- notifications

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "وضعیت اتصال", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "هشدارها", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(title: String, text: String?): Notification {
        ensureChannels()
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_close, "قطع اتصال", stop)
        if (!text.isNullOrBlank()) b.setContentText(text)
        return b.build()
    }

    private fun showAlert(title: String, text: String) {
        try {
            ensureChannels()
            val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ALERT_ID, n)
        } catch (e: Exception) {
            // notifications may be blocked
        }
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notifyStatus(title: String, text: String?) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (e: Exception) {
            // notifications may be blocked
        }
    }
}
