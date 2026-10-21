package app.vpn.common

import app.vpn.BuildConfig

/** Address of the live backend (Cloudflare Worker). */
object Backend {
    fun baseUrl(cfg: RemoteConfig?): String {
        val fromConfig = cfg?.backendUrl?.trim().orEmpty()
        val url = fromConfig.ifBlank { BuildConfig.BACKEND_URL.trim() }
        return url.trimEnd('/')
    }

    fun wsUrl(base: String): String = when {
        base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
        base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
        else -> base
    } + "/ws"
}
