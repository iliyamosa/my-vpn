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
import app.vpn.client.data.ClientStore
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

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 7001

        fun start(context: Context, link: String, name: String, key: String, bypassIran: Boolean) {
            val intent = Intent(context, XrayVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LINK, link)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_BYPASS, bypassIran)
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
    private var currentName = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startForegroundCompat(buildNotification("در حال قطع اتصال…", null))
            scope.launch { stopVpn() }
            return START_NOT_STICKY
        }

        val store = ClientStore(this)
        val link = intent?.getStringExtra(EXTRA_LINK) ?: store.lastLink
        val name = intent?.getStringExtra(EXTRA_NAME) ?: store.lastName
        val key = intent?.getStringExtra(EXTRA_KEY).orEmpty()
        val bypass = if (intent != null && intent.hasExtra(EXTRA_BYPASS)) {
            intent.getBooleanExtra(EXTRA_BYPASS, true)
        } else {
            store.lastBypass
        }

        startForegroundCompat(buildNotification("در حال اتصال…", null))

        if (link.isBlank()) {
            VpnState.error.value = "سروری انتخاب نشده است."
            scope.launch { stopVpn() }
            return START_NOT_STICKY
        }
        store.lastLink = link
        store.lastName = name
        store.lastBypass = bypass

        startJob?.cancel()
        startJob = scope.launch { startVpn(link, name, key, bypass) }
        return START_STICKY
    }

    private fun startVpn(link: String, name: String, key: String, bypass: Boolean) {
        VpnState.error.value = null
        VpnState.status.value = VpnStatus.CONNECTING
        VpnState.serverName.value = name
        VpnState.serverKey.value = key
        currentName = name
        try {
            // Switching servers: stop the previous core first.
            statsJob?.cancel()
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
                // Retry without geo files (in case they are missing on this build).
                CoreManager.stop()
                CoreManager.start(this, XrayConfigBuilder.buildVpnConfig(profile, socksPort, bypass, useGeoip = false), pfd.fd)
            }

            Http.tunnelSocksPort = socksPort
            VpnState.resetTraffic()
            VpnState.connectedAt.value = System.currentTimeMillis()
            VpnState.status.value = VpnStatus.CONNECTED
            notifyStatus("متصل به $name", null)
            startStats()
        } catch (e: Exception) {
            VpnState.error.value = e.message ?: "اتصال برقرار نشد."
            stopVpn()
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
                    notifyStatus("متصل به $currentName", "↓ ${formatBytes(down)}/s   ↑ ${formatBytes(up)}/s")
                }
            }
        }
    }

    private fun stopVpn() {
        VpnState.status.value = VpnStatus.DISCONNECTING
        statsJob?.cancel()
        Http.tunnelSocksPort = 0
        CoreManager.stop()
        closeTun()
        VpnState.connectedAt.value = 0
        VpnState.resetTraffic()
        VpnState.status.value = VpnStatus.DISCONNECTED
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        scope.launch { stopVpn() }
    }

    override fun onDestroy() {
        statsJob?.cancel()
        if (CoreManager.isRunning()) CoreManager.stop()
        closeTun()
        Http.tunnelSocksPort = 0
        if (VpnState.status.value != VpnStatus.DISCONNECTED) {
            VpnState.status.value = VpnStatus.DISCONNECTED
            VpnState.connectedAt.value = 0
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun freePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (e: Exception) {
        10808
    }

    // ---------- notification ----------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "وضعیت اتصال", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(title: String, text: String?): Notification {
        ensureChannel()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_close, "قطع اتصال", stop)
        if (!text.isNullOrBlank()) b.setContentText(text)
        return b.build()
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
