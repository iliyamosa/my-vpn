package app.vpn.client.data

import android.content.Context
import android.util.Log
import app.vpn.BuildConfig
import app.vpn.client.AppScope
import app.vpn.client.vpn.VpnState
import app.vpn.client.vpn.VpnStatus
import app.vpn.common.Backend
import app.vpn.common.Http
import app.vpn.common.RemoteConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a WebSocket open to the backend while the app is on screen or the VPN is connected.
 * The backend pushes new settings the moment the admin saves them.
 */
object Realtime {
    private const val TAG = "Realtime"
    private val started = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var lastMessageAt = 0L
    private var loopJob: Job? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        AppScope.scope.launch {
            combine(AppScope.foreground, VpnState.status) { fg, st -> fg || st == VpnStatus.CONNECTED }
                .distinctUntilChanged()
                .collect { wanted ->
                    loopJob?.cancel()
                    close()
                    if (wanted) loopJob = AppScope.scope.launch { runLoop(app) }
                }
        }
        // Background flushing of stats while the app or the VPN is active.
        AppScope.scope.launch {
            while (isActive) {
                delay(90_000)
                val active = AppScope.foreground.value || VpnState.status.value == VpnStatus.CONNECTED
                if (active && Analytics.pendingCount(app) > 0) Analytics.flush(app)
            }
        }
    }

    private suspend fun runLoop(context: Context) {
        var backoff = 2_000L
        while (true) {
            val base = Backend.baseUrl(ServerRepository.config.value)
            if (base.isBlank()) {
                delay(30_000)
                continue
            }
            val tunnelPort = Http.tunnelSocksPort
            val ok = connectOnce(context, base, tunnelPort)
            if (ok) backoff = 2_000L
            // Wait while connected; reconnect if the route changes (VPN on/off) or the socket goes quiet.
            while (socket != null) {
                delay(15_000)
                val s = socket ?: break
                if (Http.tunnelSocksPort != tunnelPort) {
                    close()
                    break
                }
                if (System.currentTimeMillis() - lastMessageAt > 75_000) {
                    close()
                    break
                }
                s.send("ping")
            }
            // Retry: immediately when the VPN route just appeared, otherwise with backoff.
            val waitUntil = System.currentTimeMillis() + backoff
            while (System.currentTimeMillis() < waitUntil) {
                if (Http.tunnelSocksPort != tunnelPort && Http.tunnelSocksPort > 0) break
                delay(1_000)
            }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun connectOnce(context: Context, base: String, tunnelPort: Int): Boolean {
        val proxy = if (tunnelPort > 0) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", tunnelPort)) else Proxy.NO_PROXY
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val url = Backend.wsUrl(base) + "?v=" + BuildConfig.VERSION_CODE
        val request = Request.Builder().url(url).header("User-Agent", "fairosvpn/${BuildConfig.VERSION_NAME}").build()
        val opened = java.util.concurrent.CountDownLatch(1)
        var success = false
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastMessageAt = System.currentTimeMillis()
                success = true
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageAt = System.currentTimeMillis()
                if (text == "pong") return
                try {
                    val o = JSONObject(text)
                    when (o.optString("type")) {
                        "config" -> {
                            val cfg = RemoteConfig.fromJson(o.getJSONObject("config"))
                            ServerRepository.applyRemoteConfig(context, cfg)
                        }
                        // Admin pushed an instant message: refresh the feed, which pops a
                        // system notification for the new broadcast (works even with the UI closed
                        // while the VPN's foreground service keeps this socket alive).
                        "notice" -> AppScope.scope.launch { NoticesStore.refresh(context) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                opened.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                opened.countDown()
            }
        })
        socket = ws
        opened.await(15, TimeUnit.SECONDS)
        if (!success) {
            ws.cancel()
            if (socket === ws) socket = null
        }
        return success
    }

    private fun close() {
        val s = socket
        socket = null
        try {
            s?.close(1000, "bye")
        } catch (e: Exception) {
            // ignore
        }
    }
}
