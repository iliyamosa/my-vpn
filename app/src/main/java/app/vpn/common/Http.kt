package app.vpn.common

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class HttpResult(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
) {
    val text: String get() = String(body, Charsets.UTF_8)
    val ok: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpException(message: String) : Exception(message)

object Http {
    const val DEFAULT_UA = "v2rayNG/1.8.5"

    /** SOCKS port of the running VPN core, or 0 when the VPN is off. Set by the VPN service. */
    @Volatile
    var tunnelSocksPort: Int = 0

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 10_000,
        viaTunnel: Boolean = false,
        maxBytes: Int = 8 * 1024 * 1024,
    ): HttpResult {
        val proxy = if (viaTunnel && tunnelSocksPort > 0) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", tunnelSocksPort))
        } else {
            Proxy.NO_PROXY
        }
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection(proxy) as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = method
                conn.useCaches = false
                if (!headers.keys.any { it.equals("User-Agent", true) }) {
                    conn.setRequestProperty("User-Agent", DEFAULT_UA)
                }
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location")
                    if (!loc.isNullOrBlank()) {
                        current = URL(URL(current), loc).toString()
                        redirects++
                        continue
                    }
                }
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
                val map = HashMap<String, String>()
                conn.headerFields?.forEach { (k, v) ->
                    if (k != null && v != null && v.isNotEmpty()) map[k.lowercase()] = v.joinToString(",")
                }
                return HttpResult(code, bytes, map)
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw HttpException("unreachable")
    }

    fun getText(url: String, timeoutMs: Int = 10_000, viaTunnel: Boolean = false, headers: Map<String, String> = emptyMap()): String {
        val r = request(url, timeoutMs = timeoutMs, viaTunnel = viaTunnel, headers = headers)
        if (!r.ok) throw HttpException("HTTP ${r.code}")
        return r.text
    }

    fun download(url: String, dest: File, timeoutMs: Int = 15_000, viaTunnel: Boolean = false): Boolean {
        return try {
            val r = request(url, timeoutMs = timeoutMs, viaTunnel = viaTunnel, maxBytes = 6 * 1024 * 1024)
            if (!r.ok || r.body.isEmpty()) return false
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.writeBytes(r.body)
            tmp.renameTo(dest)
        } catch (e: Exception) {
            false
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw HttpException("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
