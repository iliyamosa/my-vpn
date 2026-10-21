package app.vpn.client.data

import android.content.Context
import android.os.Build
import app.vpn.BuildConfig
import app.vpn.client.vpn.CoreManager
import app.vpn.client.vpn.VpnState
import app.vpn.client.vpn.XrayConfigBuilder
import app.vpn.common.Backend
import app.vpn.common.ConfigSource
import app.vpn.common.Http
import app.vpn.common.LinkParser
import app.vpn.common.RemoteConfig
import app.vpn.common.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DiagLevel { OK, WARN, FAIL, INFO }

class DiagLine(val level: DiagLevel, val title: String, val detail: String)

/** Step-by-step check of everything the app needs, with a report the user can share. */
object Diagnostics {

    suspend fun run(context: Context, onLine: (DiagLine) -> Unit) = withContext(Dispatchers.IO) {
        val tunnel = Http.tunnelSocksPort > 0
        onLine(
            DiagLine(
                DiagLevel.INFO, "برنامه",
                "نسخه ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • اندروید ${Build.VERSION.RELEASE} • ${Build.MANUFACTURER} ${Build.MODEL}" +
                    " • VPN: ${VpnState.status.value}",
            ),
        )

        // 1. Settings sources
        val cfgNow = ServerRepository.config.value
        onLine(
            DiagLine(
                DiagLevel.INFO, "تنظیمات فعلی",
                "نسخه‌ی ${cfgNow.revision} • ${cfgNow.free.subscriptions.distinct().size} لینک اشتراک رایگان • " +
                    "${cfgNow.free.servers.size} کانفیگ تکی • ${cfgNow.free.snapshot.size} سرور پشتیبان",
            ),
        )
        val backend = Backend.baseUrl(cfgNow)
        val sources = listOfNotNull(backend.takeIf { it.isNotBlank() }?.let { "$it/config" }) + ConfigSource.defaultUrls()
        for (url in sources) {
            val host = hostOf(url)
            val start = System.currentTimeMillis()
            try {
                val text = Http.getText("$url?t=${System.currentTimeMillis()}", timeoutMs = 8_000, viaTunnel = tunnel)
                val rev = RemoteConfig.parse(text).revision
                val ms = System.currentTimeMillis() - start
                val level = if (rev >= cfgNow.revision) DiagLevel.OK else DiagLevel.WARN
                onLine(DiagLine(level, "منبع تنظیمات: $host", "نسخه‌ی $rev • $ms میلی‌ثانیه" + if (rev < cfgNow.revision) " (قدیمی است)" else ""))
            } catch (e: Exception) {
                onLine(DiagLine(DiagLevel.FAIL, "منبع تنظیمات: $host", describeError(e)))
            }
        }

        // 2. Free subscriptions
        for (url in cfgNow.free.subscriptions.distinct()) {
            val host = hostOf(url)
            val start = System.currentTimeMillis()
            try {
                val r = SubscriptionFetcher.fetch(url, viaTunnel = tunnel)
                val ms = System.currentTimeMillis() - start
                if (r.servers.isEmpty()) {
                    onLine(DiagLine(DiagLevel.FAIL, "اشتراک رایگان: $host", "پاسخ آمد ولی سروری در آن نبود • $ms میلی‌ثانیه"))
                } else {
                    val usage = r.info?.let { if (it.total > 0) " • مصرف ${app.vpn.common.formatBytes(it.used)} از ${app.vpn.common.formatBytes(it.total)}" else "" }.orEmpty()
                    val expired = if (r.info?.isExpired() == true) " • منقضی شده!" else ""
                    val full = if (r.info?.isOutOfData() == true) " • حجم تمام شده!" else ""
                    val lvl = if (expired.isNotEmpty() || full.isNotEmpty()) DiagLevel.FAIL else DiagLevel.OK
                    onLine(DiagLine(lvl, "اشتراک رایگان: $host", "${r.servers.size} سرور • $ms میلی‌ثانیه$usage$expired$full"))
                }
            } catch (e: Exception) {
                onLine(DiagLine(DiagLevel.FAIL, "اشتراک رایگان: $host", describeError(e)))
            }
        }
        if (cfgNow.free.subscriptions.isEmpty() && cfgNow.free.servers.isEmpty()) {
            onLine(DiagLine(DiagLevel.FAIL, "سرور رایگان", "در تنظیمات هیچ لینک اشتراک یا کانفیگ رایگانی نیست."))
        }

        // 3. Pro subscription
        val pro = ClientStore(context).proLink
        if (pro.isNotBlank()) {
            try {
                val r = SubscriptionFetcher.fetch(pro, viaTunnel = tunnel)
                onLine(DiagLine(if (r.servers.isEmpty()) DiagLevel.FAIL else DiagLevel.OK, "اشتراک پرو", "${r.servers.size} سرور"))
            } catch (e: Exception) {
                onLine(DiagLine(DiagLevel.FAIL, "اشتراک پرو", describeError(e)))
            }
        }

        // 4. Server list and real reachability of a few servers
        val list = ServerRepository.usableServers(context)
        onLine(DiagLine(if (list.isEmpty()) DiagLevel.FAIL else DiagLevel.OK, "لیست سرورهای برنامه", "${list.size} سرور قابل اتصال"))
        val testUrl = cfgNow.testUrl
        for (p in list.take(4)) {
            val d = CoreManager.measureDelay(context, XrayConfigBuilder.buildTestConfig(p), testUrl)
            onLine(
                DiagLine(
                    if (d >= 0) DiagLevel.OK else DiagLevel.FAIL,
                    "تست سرور: ${p.remarks}",
                    if (d >= 0) "$d میلی‌ثانیه" else "سرور پاسخ نداد",
                ),
            )
        }
    }

    fun report(lines: List<DiagLine>): String = buildString {
        append("گزارش عیب‌یابی ${BuildConfig.VERSION_NAME}\n")
        for (l in lines) {
            val mark = when (l.level) {
                DiagLevel.OK -> "✅"
                DiagLevel.WARN -> "⚠️"
                DiagLevel.FAIL -> "❌"
                DiagLevel.INFO -> "ℹ️"
            }
            append("$mark ${l.title}: ${l.detail}\n")
        }
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url.take(30)
    } catch (e: Exception) {
        url.take(30)
    }

    @Suppress("unused")
    private fun parseCount(text: String) = LinkParser.parseMany(text).size
}
