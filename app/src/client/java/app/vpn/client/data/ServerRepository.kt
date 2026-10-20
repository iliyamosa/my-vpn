package app.vpn.client.data

import android.content.Context
import app.vpn.client.vpn.CoreManager
import app.vpn.client.vpn.XrayConfigBuilder
import app.vpn.common.ConfigSource
import app.vpn.common.Http
import app.vpn.common.LinkParser
import app.vpn.common.ManualServer
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
    val proServers = MutableStateFlow<List<ServerProfile>>(emptyList())
    val proInfo = MutableStateFlow<SubscriptionInfo?>(null)
    val proInfoLines = MutableStateFlow<List<String>>(emptyList())
    val delays = MutableStateFlow<Map<String, Long>>(emptyMap())
    val refreshing = MutableStateFlow(false)
    val testing = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

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
        freeServers.value = parseEntries(readEntries(store, ClientStore.FILE_FREE), config.value.free.hideKeywords)
        proServers.value = parseEntries(readEntries(store, ClientStore.FILE_PRO), emptyList())
        proInfo.value = SubscriptionInfo.parse(store.proInfoHeader)
        proInfoLines.value = store.readList(ClientStore.FILE_PRO_INFO_LINES)
    }

    // ---------- pro status ----------

    fun isPro(context: Context): Boolean {
        val store = ClientStore(context)
        if (store.proLink.isBlank()) return false
        if (proServers.value.isEmpty()) return false
        val info = proInfo.value ?: return true
        return !info.isExpired() && !info.isOutOfData()
    }

    fun proProblem(context: Context): String? {
        val store = ClientStore(context)
        if (store.proLink.isBlank()) return null
        val info = proInfo.value
        return when {
            info?.isExpired() == true -> "اشتراک پرو شما تمام شده است."
            info?.isOutOfData() == true -> "حجم اشتراک پرو شما تمام شده است."
            proServers.value.isEmpty() -> "سروری در اشتراک پرو شما پیدا نشد."
            else -> null
        }
    }

    /** All servers the user may connect to. */
    fun usableServers(context: Context): List<ServerProfile> {
        val list = mutableListOf<ServerProfile>()
        if (isPro(context)) list.addAll(proServers.value)
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
        val urls = (ConfigSource.defaultUrls() + config.value.mirrors).distinct()
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
        if (best.revision >= config.value.revision) {
            config.value = best
            ClientStore(context).writeFile(ClientStore.FILE_CONFIG, best.toJsonString())
            launch(Dispatchers.IO) { AdCache.prefetch(context, best) }
        }
    }

    private suspend fun refreshFree(context: Context) = coroutineScope {
        val cfg = config.value.free
        val viaTunnel = Http.tunnelSocksPort > 0
        val entries = mutableListOf<ManualServer>()
        entries.addAll(cfg.servers)
        var anySubFailed = false
        val fromSubs = cfg.subscriptions.map { url ->
            async(Dispatchers.IO) {
                try {
                    SubscriptionFetcher.fetch(url, viaTunnel = viaTunnel).servers.map { ManualServer("", it.rawLink) }
                } catch (e: Exception) {
                    anySubFailed = true
                    null
                }
            }
        }.awaitAll()
        fromSubs.forEach { list -> if (list != null) entries.addAll(list) }
        if (anySubFailed && entries.size == cfg.servers.size && freeServers.value.isNotEmpty()) {
            // Network problem: keep the old list instead of wiping it.
            return@coroutineScope
        }
        val store = ClientStore(context)
        writeEntries(store, ClientStore.FILE_FREE, entries)
        freeServers.value = parseEntries(entries, cfg.hideKeywords)
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

/** Downloads ad images ahead of time so they show instantly before connecting. */
object AdCache {
    fun fileFor(context: Context, url: String): File {
        val dir = File(context.filesDir, "ads").apply { mkdirs() }
        return File(dir, sha1(url))
    }

    fun prefetch(context: Context, cfg: RemoteConfig) {
        val viaTunnel = Http.tunnelSocksPort > 0
        cfg.ads.activeItems().map { it.imageUrl }.filter { it.isNotBlank() }.distinct().forEach { url ->
            val f = fileFor(context, url)
            if (!f.exists() || f.length() == 0L) Http.download(url, f, viaTunnel = viaTunnel)
        }
    }
}
