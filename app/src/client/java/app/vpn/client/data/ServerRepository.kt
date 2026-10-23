package app.vpn.client.data

import android.content.Context
import app.vpn.client.AppScope
import app.vpn.client.vpn.CoreManager
import app.vpn.client.vpn.XrayConfigBuilder
import app.vpn.common.Backend
import app.vpn.common.ConfigSource
import app.vpn.common.Http
import app.vpn.common.LinkParser
import app.vpn.common.ManualServer
import app.vpn.common.ProServer
import app.vpn.common.RemoteConfig
import app.vpn.common.ServerProfile
import app.vpn.common.SubscriptionFetcher
import app.vpn.common.SubscriptionInfo
import app.vpn.common.sha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Holds the admin config, free servers and the user's pro servers. */
object ServerRepository {
    const val DELAY_TESTING = -2L
    const val DELAY_FAILED = -1L

    val config = MutableStateFlow(RemoteConfig())
    val freeServers = MutableStateFlow<List<ServerProfile>>(emptyList())
    /** Pro server profiles the signed-in pro account may use (empty for free users). */
    val proServers = MutableStateFlow<List<ServerProfile>>(emptyList())
    /** Pro location names shown locked+starred to free users. */
    val proShowcase = MutableStateFlow<List<String>>(emptyList())
    /** Whether the signed-in account currently has pro. */
    val accountIsPro = MutableStateFlow(false)
    val proInfo = MutableStateFlow<SubscriptionInfo?>(null)
    val proInfoLines = MutableStateFlow<List<String>>(emptyList())
    val delays = MutableStateFlow<Map<String, Long>>(emptyMap())
    val refreshing = MutableStateFlow(false)
    val testing = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    /** Why the free subscription could not be read (shown on the home screen and in diagnostics). */
    val lastFreeError = MutableStateFlow<String?>(null)

    @Volatile
    private var loaded = false
    private var lastTestAt = 0L
    private val refreshMutex = Mutex()

    fun loadCache(context: Context) {
        if (loaded) return
        loaded = true
        val store = ClientStore(context)
        val cached = store.readFile(ClientStore.FILE_CONFIG)?.let { text ->
            try {
                RemoteConfig.parse(text)
            } catch (e: Exception) {
                null
            }
        }
        val bundled = try {
            context.assets.open("bundled_config.json").use { RemoteConfig.parse(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (e: Exception) {
            null
        }
        val best = listOfNotNull(cached, bundled).maxByOrNull { it.revision }
        if (best != null) config.value = best
        val cachedFree = parseEntries(readEntries(store, ClientStore.FILE_FREE), config.value.free.hideKeywords)
        freeServers.value = cachedFree.ifEmpty { parseEntries(snapshotEntries(config.value), config.value.free.hideKeywords) }
        proServers.value = parseEntries(readEntries(store, ClientStore.FILE_PRO), emptyList())
        // Restore pro status from the cached account so pro works offline until /me refreshes.
        accountIsPro.value = try {
            val j = store.accountJson
            j.isNotBlank() && org.json.JSONObject(j).optLong("proUntil") > System.currentTimeMillis()
        } catch (e: Exception) {
            false
        }
    }

    // ---------- pro status (account-based) ----------

    /** Feeds pro servers from the signed-in account (/me). Names are shown to everyone; links only to pro. */
    fun setProServers(context: Context, servers: List<ProServer>, showcase: List<String>, isPro: Boolean) {
        accountIsPro.value = isPro
        proShowcase.value = showcase.distinct()
        val parsed = parseEntries(servers.map { ManualServer(it.name, it.link) }, emptyList())
        proServers.value = parsed
        writeEntries(ClientStore(context), ClientStore.FILE_PRO, servers.map { ManualServer(it.name, it.link) })
        if (!isPro) {
            val store = ClientStore(context)
            if (store.selectedKey != ClientStore.AUTO && proServers.value.none { it.key == store.selectedKey } && freeServers.value.none { it.key == store.selectedKey }) {
                store.selectedKey = ClientStore.AUTO
            }
        }
    }

    fun isPro(context: Context): Boolean = accountIsPro.value

    /** All servers the user may connect to. */
    fun usableServers(context: Context): List<ServerProfile> {
        val list = mutableListOf<ServerProfile>()
        if (accountIsPro.value) list.addAll(proServers.value)
        if (config.value.free.enabled) list.addAll(freeServers.value)
        return list.distinctBy { it.key }
    }

    // ---------- refresh ----------

    suspend fun refreshAll(context: Context) {
        if (!refreshMutex.tryLock()) return
        try {
            refreshing.value = true
            lastError.value = null
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val cfgJob = async { refreshConfig(context) }
                    val proJob = async { refreshPro(context) }
                    cfgJob.await()
                    refreshFree(context)
                    proJob.await()
                }
            }
            if (freeServers.value.isEmpty() && proServers.value.isEmpty()) {
                lastError.value = "دریافت لیست سرورها انجام نشد. اینترنت خود را بررسی کنید."
            }
        } finally {
            refreshing.value = false
            refreshMutex.unlock()
        }
    }

    private suspend fun refreshConfig(context: Context) = coroutineScope {
        val backend = Backend.baseUrl(config.value)
        val urls = (listOfNotNull(backend.takeIf { it.isNotBlank() }?.let { "$it/config" }) +
            ConfigSource.defaultUrls() + config.value.mirrors).distinct()
        if (urls.isEmpty()) return@coroutineScope
        val viaTunnel = Http.tunnelSocksPort > 0
        val bust = System.currentTimeMillis() / 60_000
        val results = urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    val sep = if (url.contains("?")) "&" else "?"
                    RemoteConfig.parse(Http.getText("$url${sep}t=$bust", timeoutMs = 8_000, viaTunnel = viaTunnel))
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
        val best = results.maxByOrNull { it.revision } ?: return@coroutineScope
        applyRemoteConfig(context, best, refreshServers = false)
    }

    /**
     * Applies newer settings (from a download or pushed live by the backend).
     * Reloads the free server list when its sources changed.
     */
    fun applyRemoteConfig(context: Context, cfg: RemoteConfig, refreshServers: Boolean = true) {
        val old = config.value
        if (cfg.revision < old.revision) return
        if (cfg == old) return
        config.value = cfg
        ClientStore(context).writeFile(ClientStore.FILE_CONFIG, cfg.toJsonString())
        AppScope.scope.launch { AdCache.prefetch(context, cfg) }
        val freeChanged = cfg.free.subscriptions != old.free.subscriptions ||
            cfg.free.servers != old.free.servers ||
            cfg.free.hideKeywords != old.free.hideKeywords
        if (refreshServers && freeChanged) {
            AppScope.scope.launch {
                try {
                    refreshFree(context)
                } catch (e: Exception) {
                    // keep old list
                }
            }
        }
    }

    private suspend fun refreshFree(context: Context) = coroutineScope {
        val full = config.value
        val cfg = full.free
        val viaTunnel = Http.tunnelSocksPort > 0
        val entries = mutableListOf<ManualServer>()
        entries.addAll(cfg.servers)
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fromSubs = cfg.subscriptions.distinct().map { url ->
            async(Dispatchers.IO) {
                try {
                    val r = SubscriptionFetcher.fetch(url, viaTunnel = viaTunnel)
                    if (r.servers.isEmpty()) errors.add("${hostOf(url)}: سروری در پاسخ نبود")
                    r.servers.map { ManualServer("", it.rawLink) }
                } catch (e: Exception) {
                    errors.add("${hostOf(url)}: ${describeError(e)}")
                    null
                }
            }
        }.awaitAll()
        var gotFromSubs = false
        fromSubs.forEach { list ->
            if (!list.isNullOrEmpty()) {
                entries.addAll(list)
                gotFromSubs = true
            }
        }
        lastFreeError.value = if (errors.isEmpty()) null else errors.joinToString("\n")
        if (cfg.subscriptions.isNotEmpty() && !gotFromSubs) {
            // The subscription could not be read on this network: keep the last good list,
            // or fall back to the copy saved by the admin app.
            if (freeServers.value.isNotEmpty() && entries.size == cfg.servers.size) return@coroutineScope
            entries.addAll(snapshotEntries(full))
        }
        val store = ClientStore(context)
        writeEntries(store, ClientStore.FILE_FREE, entries)
        freeServers.value = parseEntries(entries, cfg.hideKeywords)
    }

    private fun snapshotEntries(cfg: RemoteConfig): List<ManualServer> = cfg.free.snapshot.map { ManualServer("", it) }

    private fun hostOf(url: String): String = try {
        java.net.URI(url.trim()).host ?: url.take(30)
    } catch (e: Exception) {
        url.take(30)
    }

    private fun refreshPro(context: Context) {
        val store = ClientStore(context)
        val link = store.proLink
        if (link.isBlank()) return
        try {
            val r = SubscriptionFetcher.fetch(link, viaTunnel = Http.tunnelSocksPort > 0)
            applyPro(store, r.servers.map { ManualServer("", it.rawLink) }, r.info, r.infoLines)
        } catch (e: Exception) {
            // keep cached pro servers
        }
    }

    private fun applyPro(store: ClientStore, entries: List<ManualServer>, info: SubscriptionInfo?, lines: List<String>) {
        writeEntries(store, ClientStore.FILE_PRO, entries)
        proServers.value = parseEntries(entries, emptyList())
        proInfo.value = info
        store.proInfoHeader = if (info == null) "" else
            "upload=${info.upload}; download=${info.download}; total=${info.total}; expire=${info.expire}"
        proInfoLines.value = lines
        store.writeList(ClientStore.FILE_PRO_INFO_LINES, lines)
    }

    /** Validates and saves a pro subscription link. Returns an error message or null on success. */
    suspend fun activatePro(context: Context, link: String): String? = withContext(Dispatchers.IO) {
        val clean = link.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return@withContext "لینک اشتراک باید با http یا https شروع شود."
        }
        try {
            val r = SubscriptionFetcher.fetch(clean, viaTunnel = Http.tunnelSocksPort > 0)
            if (r.servers.isEmpty()) return@withContext "در این لینک سروری پیدا نشد."
            if (r.info?.isExpired() == true) return@withContext "این اشتراک منقضی شده است."
            val store = ClientStore(context)
            store.proLink = clean
            applyPro(store, r.servers.map { ManualServer("", it.rawLink) }, r.info, r.infoLines)
            null
        } catch (e: Exception) {
            "دریافت اشتراک انجام نشد: ${e.message ?: "خطای شبکه"}"
        }
    }

    fun removePro(context: Context) {
        val store = ClientStore(context)
        store.proLink = ""
        store.proInfoHeader = ""
        writeEntries(store, ClientStore.FILE_PRO, emptyList())
        store.writeList(ClientStore.FILE_PRO_INFO_LINES, emptyList())
        proServers.value = emptyList()
        proInfo.value = null
        proInfoLines.value = emptyList()
        if (store.selectedKey != ClientStore.AUTO && freeServers.value.none { it.key == store.selectedKey }) {
            store.selectedKey = ClientStore.AUTO
        }
    }

    // ---------- delay tests ----------

    suspend fun testServers(context: Context, list: List<ServerProfile>, overallTimeoutMs: Long = 20_000) {
        if (list.isEmpty()) return
        testing.value = true
        val url = config.value.testUrl
        val results = ConcurrentHashMap<String, Long>()
        delays.value = delays.value + list.associate { it.key to DELAY_TESTING }
        try {
            withTimeoutOrNull(overallTimeoutMs) {
                coroutineScope {
                    val sem = Semaphore(6)
                    list.map { p ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                val d = CoreManager.measureDelay(context, XrayConfigBuilder.buildTestConfig(p), url)
                                results[p.key] = d
                                delays.value = delays.value + (p.key to d)
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            val finalMap = delays.value.toMutableMap()
            list.forEach { p -> if (finalMap[p.key] == DELAY_TESTING) finalMap[p.key] = results[p.key] ?: DELAY_FAILED }
            delays.value = finalMap
            lastTestAt = System.currentTimeMillis()
            testing.value = false
        }
    }

    /** Picks the server to connect to: the selected one, or the fastest for "auto". */
    suspend fun resolveServer(context: Context): ServerProfile? {
        val store = ClientStore(context)
        val usable = usableServers(context)
        if (usable.isEmpty()) return null
        val selected = store.selectedKey
        if (selected != ClientStore.AUTO) {
            usable.firstOrNull { it.key == selected }?.let { return it }
        }
        val candidates = if (isPro(context) && proServers.value.isNotEmpty()) {
            proServers.value.filter { p -> usable.any { it.key == p.key } }
        } else {
            usable
        }
        val fresh = System.currentTimeMillis() - lastTestAt < 5 * 60_000
        if (!fresh || candidates.none { (delays.value[it.key] ?: -1) > 0 }) {
            testServers(context, candidates.take(15), overallTimeoutMs = 9_000)
        }
        val best = candidates
            .mapNotNull { p -> delays.value[p.key]?.takeIf { it > 0 }?.let { p to it } }
            .minByOrNull { it.second }?.first
        return best ?: candidates.first()
    }

    // ---------- helpers ----------

    private fun parseEntries(entries: List<ManualServer>, hideKeywords: List<String>): List<ServerProfile> =
        entries.mapNotNull { e ->
            val p = LinkParser.parse(e.link) ?: return@mapNotNull null
            if (p.isInfoOnly) return@mapNotNull null
            val named = if (e.name.isNotBlank()) p.copy(remarks = e.name) else p
            if (hideKeywords.any { k -> named.remarks.contains(k, ignoreCase = true) }) null else named
        }.distinctBy { it.key }

    private fun readEntries(store: ClientStore, file: String): List<ManualServer> = try {
        val text = store.readFile(file) ?: "[]"
        val arr = JSONArray(text)
        (0 until arr.length()).map { ManualServer.fromJson(arr.optJSONObject(it)) }.filter { it.link.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeEntries(store: ClientStore, file: String, entries: List<ManualServer>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        store.writeFile(file, arr.toString())
    }
}

/**
 * Downloads ad images and videos ahead of time. Ads play before the VPN connects, when many
 * sites are unreachable, so media is fetched earlier (usually through the VPN) and kept on the phone.
 */
object AdCache {
    private const val MAX_VIDEO_BYTES = 30 * 1024 * 1024
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun fileFor(context: Context, url: String): File {
        val dir = File(context.filesDir, "ads").apply { mkdirs() }
        return File(dir, sha1(url))
    }

    fun isCached(context: Context, url: String): Boolean {
        val f = fileFor(context, url)
        return f.exists() && f.length() > 0
    }

    fun prefetch(context: Context, cfg: RemoteConfig) {
        if (!running.compareAndSet(false, true)) return
        try {
            val viaTunnel = Http.tunnelSocksPort > 0
            val items = cfg.ads.activeItems()
            val wanted = (items.map { it.imageUrl } + items.map { it.videoUrl }).filter { it.isNotBlank() }.distinct()
            wanted.forEach { url ->
                val f = fileFor(context, url)
                if (!f.exists() || f.length() == 0L) downloadLarge(url, f, viaTunnel)
            }
            // Remove media of ads that no longer exist.
            val keep = wanted.map { fileFor(context, it).name }.toSet()
            File(context.filesDir, "ads").listFiles()?.forEach { if (it.name !in keep) it.delete() }
        } finally {
            running.set(false)
        }
    }

    /** Streams to disk (videos are too big to hold in memory). */
    private fun downloadLarge(url: String, dest: File, viaTunnel: Boolean): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val proxy = if (viaTunnel && Http.tunnelSocksPort > 0) {
                java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", Http.tunnelSocksPort))
            } else {
                java.net.Proxy.NO_PROXY
            }
            var current = url
            var conn: java.net.HttpURLConnection
            var redirects = 0
            while (true) {
                conn = java.net.URL(current).openConnection(proxy) as java.net.HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", Http.DEFAULT_UA)
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    current = java.net.URL(java.net.URL(current), conn.getHeaderField("Location")).toString()
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    return false
                }
                break
            }
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_VIDEO_BYTES) throw java.io.IOException("too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            conn.disconnect()
            total > 0 && tmp.renameTo(dest)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}

/** Short, readable reason for a network failure. */
fun describeError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "آدرس پیدا نشد (DNS / فیلتر)"
    is java.net.SocketTimeoutException -> "پاسخی نیامد (زمان تمام شد)"
    is java.net.ConnectException -> "اتصال برقرار نشد"
    is javax.net.ssl.SSLException -> "خطای امنیتی TLS (احتمال فیلتر)"
    is app.vpn.common.HttpException -> e.message ?: "خطای HTTP"
    else -> "${e.javaClass.simpleName}: ${e.message ?: ""}".take(120)
}
