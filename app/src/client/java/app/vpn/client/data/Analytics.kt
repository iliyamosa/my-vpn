package app.vpn.client.data

import android.content.Context
import android.os.Build
import app.vpn.BuildConfig
import app.vpn.common.Backend
import app.vpn.common.Http
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Anonymous usage stats. Only a random device id, phone model, Android/app version and
 * app events (ad views, connects, ratings) are sent. No IP, no sites, no traffic.
 */
object Analytics {
    private const val FILE = "events_queue.json"
    private const val MAX_QUEUE = 1000

    private val lock = Any()
    private val queue = ArrayList<JSONObject>()
    private var loaded = false
    private val flushMutex = Mutex()

    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("client", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(context.filesDir, FILE)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { queue.add(it) }
            }
        } catch (e: Exception) {
            // ignore broken queue
        }
    }

    private fun save(context: Context) {
        try {
            val arr = JSONArray()
            queue.forEach { arr.put(it) }
            val f = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun track(context: Context, type: String, server: String? = null, id: String? = null, extra: Map<String, Any>? = null) {
        val ev = JSONObject().put("t", type).put("ts", System.currentTimeMillis())
        if (!server.isNullOrBlank()) ev.put("s", server.take(80))
        if (!id.isNullOrBlank()) ev.put("id", id.take(60))
        extra?.forEach { (k, v) -> ev.put(k, v) }
        synchronized(lock) {
            load(context)
            queue.add(ev)
            while (queue.size > MAX_QUEUE) queue.removeAt(0)
            save(context)
        }
    }

    fun pendingCount(context: Context): Int = synchronized(lock) {
        load(context)
        queue.size
    }

    /** Sends queued events (directly, or through the VPN tunnel). Returns true when the queue is empty. */
    suspend fun flush(context: Context): Boolean = flushMutex.withLock {
        val base = Backend.baseUrl(ServerRepository.config.value)
        if (base.isBlank()) return@withLock false
        val batch: List<JSONObject> = synchronized(lock) {
            load(context)
            queue.take(300).toList()
        }
        if (batch.isEmpty()) return@withLock true
        val body = JSONObject()
            .put("d", deviceId(context))
            .put("m", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60))
            .put("a", Build.VERSION.RELEASE ?: "")
            .put("v", BuildConfig.VERSION_CODE)
            .put("p", if (ServerRepository.isPro(context)) 1 else 0)
            .put("e", JSONArray().also { arr -> batch.forEach { arr.put(it) } })
            .toString()
            .toByteArray(Charsets.UTF_8)

        val routes = if (Http.tunnelSocksPort > 0) listOf(true, false) else listOf(false)
        for (viaTunnel in routes) {
            try {
                val r = Http.request(
                    "$base/events",
                    method = "POST",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = body,
                    timeoutMs = 8_000,
                    viaTunnel = viaTunnel,
                )
                if (r.ok || r.code == 400) {
                    // 400 = rejected batch; drop it instead of retrying forever.
                    synchronized(lock) {
                        val sent = batch.toSet()
                        queue.removeAll { it in sent }
                        save(context)
                    }
                    return@withLock synchronized(lock) { queue.isEmpty() }
                }
                if (r.code == 429) return@withLock false
            } catch (e: Exception) {
                // try the next route
            }
        }
        false
    }
}
