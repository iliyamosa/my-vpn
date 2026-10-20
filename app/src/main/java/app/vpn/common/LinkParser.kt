package app.vpn.common

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** One server parsed from a share link (vless://, vmess://, trojan://, ss://). */
data class ServerProfile(
    val protocol: String,
    val remarks: String,
    val address: String,
    val port: Int,
    val id: String = "",
    val password: String = "",
    val method: String = "",
    val encryption: String = "none",
    val flow: String = "",
    val network: String = "tcp",
    val headerType: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val authority: String = "",
    val extra: String = "",
    val security: String = "",
    val sni: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val mldsa65Verify: String = "",
    val echConfigList: String = "",
    val verifyPeerCertByName: String = "",
    val pinnedPeerCertSha256: String = "",
    val insecure: Boolean = false,
    val rawLink: String = "",
) {
    /** Stable id of this server, used to remember the user's selection. */
    val key: String by lazy { sha1("$protocol|$address|$port|$id|$password|$network|$path|$host|$sni|$remarks") }

    /** Placeholder entries some panels add only to show a message (traffic left, "update your link", ...). */
    val isInfoOnly: Boolean
        get() = address in INFO_ADDRESSES || port <= 0 || port > 65535

    companion object {
        val INFO_ADDRESSES = setOf("1.1.1.1", "0.0.0.0", "127.0.0.1", "8.8.8.8", "localhost")
    }
}

object LinkParser {

    /** Parses one share link, returns null when it is not supported or broken. */
    fun parse(rawLine: String): ServerProfile? {
        val line = rawLine.trim()
        return try {
            when {
                line.startsWith("vless://", true) -> parseStandard(line, "vless")
                line.startsWith("trojan://", true) -> parseStandard(line, "trojan")
                line.startsWith("vmess://", true) -> parseVmess(line)
                line.startsWith("ss://", true) -> parseShadowsocks(line)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Parses many links separated by new lines / spaces (also accepts a base64 blob). */
    fun parseMany(text: String): List<ServerProfile> {
        val decoded = decodeSubscriptionBody(text)
        return decoded.lines()
            .flatMap { it.trim().split(Regex("\\s+")) }
            .filter { it.contains("://") }
            .mapNotNull { parse(it) }
    }

    /** A subscription body is usually base64 of newline separated links; sometimes it's plain text. */
    fun decodeSubscriptionBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.contains("://")) return trimmed
        val decoded = decodeBase64(trimmed.replace("\n", "").replace("\r", ""))
        return if (decoded != null && decoded.contains("://")) decoded else trimmed
    }

    /** java.net.URI leaves host empty for names with '_' etc.; read it from the authority instead. */
    private fun hostPort(uri: URI): Pair<String, Int>? {
        val h = uri.host
        if (!h.isNullOrBlank() && uri.port > 0) return h to uri.port
        val authority = uri.rawAuthority ?: return null
        val hp = authority.substringAfterLast("@")
        val idx = hp.lastIndexOf(':')
        if (idx <= 0) return null
        val port = hp.substring(idx + 1).toIntOrNull() ?: return null
        return hp.substring(0, idx) to port
    }

    private fun parseStandard(link: String, protocol: String): ServerProfile? {
        val uri = URI(fixUrl(link))
        val (host, port) = hostPort(uri) ?: return null
        if (port <= 0) return null
        val userInfo = decode(uri.rawUserInfo ?: return null)
        val q = queryMap(uri.rawQuery)
        val remarks = decode(uri.rawFragment.orEmpty()).ifBlank { "$host:$port" }
        var network = q["type"]?.ifBlank { null } ?: "tcp"
        if (network == "raw") network = "tcp"
        if (network == "http") network = "h2"
        var security = q["security"].orEmpty()
        if (security != "tls" && security != "reality") security = ""
        return ServerProfile(
            protocol = protocol,
            remarks = remarks,
            address = host.removeSurrounding("[", "]"),
            port = port,
            id = if (protocol == "vless") userInfo else "",
            password = if (protocol == "trojan") userInfo else "",
            encryption = q["encryption"]?.ifBlank { null } ?: "none",
            flow = q["flow"].orEmpty(),
            network = network,
            headerType = q["headerType"].orEmpty(),
            host = q["host"].orEmpty(),
            path = q["path"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
            mode = q["mode"].orEmpty(),
            authority = q["authority"].orEmpty(),
            extra = q["extra"].orEmpty(),
            security = if (protocol == "trojan" && security.isEmpty() && q["security"] == null) "tls" else security,
            sni = q["sni"] ?: q["peer"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            alpn = q["alpn"].orEmpty(),
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            mldsa65Verify = q["pqv"].orEmpty(),
            echConfigList = q["ech"].orEmpty(),
            verifyPeerCertByName = q["vcn"].orEmpty(),
            pinnedPeerCertSha256 = q["pcs"].orEmpty(),
            insecure = listOf("insecure", "allowInsecure", "allow_insecure").any { q[it] == "1" || q[it] == "true" },
            rawLink = link,
        )
    }

    private fun parseVmess(link: String): ServerProfile? {
        val payload = link.substring("vmess://".length).substringBefore("#")
        val json = decodeBase64(payload) ?: return null
        val o = JSONObject(json)
        val address = o.optString("add").trim()
        val port = o.opt("port")?.toString()?.trim()?.toIntOrNull() ?: return null
        if (address.isEmpty()) return null
        var network = o.optString("net", "tcp").ifBlank { "tcp" }
        if (network == "raw") network = "tcp"
        if (network == "http") network = "h2"
        val type = o.optString("type", "")
        val tls = o.optString("tls", "")
        return ServerProfile(
            protocol = "vmess",
            remarks = o.optString("ps", "").ifBlank { "$address:$port" },
            address = address,
            port = port,
            id = o.optString("id", ""),
            method = o.optString("scy", "").ifBlank { "auto" },
            network = network,
            headerType = if (network == "grpc") "" else type,
            host = o.optString("host", ""),
            path = if (network == "grpc") "" else o.optString("path", ""),
            serviceName = if (network == "grpc") o.optString("path", "") else "",
            mode = if (network == "grpc") type else "",
            authority = if (network == "grpc") o.optString("host", "") else "",
            security = if (tls == "tls" || tls == "reality") tls else "",
            sni = o.optString("sni", ""),
            fingerprint = o.optString("fp", ""),
            alpn = o.optString("alpn", ""),
            insecure = o.optString("allowInsecure", "") == "1" || o.optString("insecure", "") == "1",
            rawLink = link,
        )
    }

    private fun parseShadowsocks(link: String): ServerProfile? {
        val noScheme = link.substring("ss://".length)
        val fragment = noScheme.substringAfter("#", "")
        val remarksRaw = decode(fragment)
        val main = noScheme.substringBefore("#")

        // SIP002: ss://base64(method:password)@host:port?plugin=...  or  ss://method:password@host:port
        if (main.contains("@")) {
            val uri = URI(fixUrl("ss://$main"))
            val (host, port) = hostPort(uri) ?: return null
            if (port <= 0) return null
            val userInfoRaw = uri.rawUserInfo ?: return null
            val userInfo = decode(userInfoRaw)
            val creds = if (userInfo.contains(":")) userInfo else (decodeBase64(userInfoRaw) ?: return null)
            val method = creds.substringBefore(":")
            val password = creds.substringAfter(":", "")
            return ServerProfile(
                protocol = "shadowsocks",
                remarks = remarksRaw.ifBlank { "$host:$port" },
                address = host.removeSurrounding("[", "]"),
                port = port,
                method = method,
                password = password,
                rawLink = link,
            )
        }

        // Legacy: ss://base64(method:password@host:port)
        val decoded = decodeBase64(main.substringBefore("?")) ?: return null
        val m = Regex("^(.+?):(.*)@(.+?):(\\d+)/?$").matchEntire(decoded.trim()) ?: return null
        return ServerProfile(
            protocol = "shadowsocks",
            remarks = remarksRaw.ifBlank { "${m.groupValues[3]}:${m.groupValues[4]}" },
            address = m.groupValues[3].removeSurrounding("[", "]"),
            port = m.groupValues[4].toInt(),
            method = m.groupValues[1].lowercase(),
            password = m.groupValues[2],
            rawLink = link,
        )
    }

    private fun queryMap(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            map[decode(k)] = decode(v)
        }
        return map
    }

    /** URLDecoder turns '+' into space; share links use %20 for spaces and '+' literally in base64 values. */
    fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }

    /** java.net.URI rejects some characters that appear in real links; escape them. */
    private fun fixUrl(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                ' ' -> sb.append("%20")
                '|' -> sb.append("%7C")
                '"' -> sb.append("%22")
                '<' -> sb.append("%3C")
                '>' -> sb.append("%3E")
                '{' -> sb.append("%7B")
                '}' -> sb.append("%7D")
                '\\' -> sb.append("%5C")
                '^' -> sb.append("%5E")
                '`' -> sb.append("%60")
                else -> if (c.code > 127) {
                    c.toString().toByteArray(Charsets.UTF_8).forEach { b -> sb.append("%%%02X".format(b.toInt() and 0xff)) }
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    fun decodeBase64(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val flagsList = listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
        for (flags in flagsList) {
            try {
                var padded = s
                val rem = padded.length % 4
                if (rem != 0) padded += "=".repeat(4 - rem)
                val bytes = Base64.decode(padded, flags)
                val text = String(bytes, Charsets.UTF_8)
                if (text.isNotEmpty()) return text
            } catch (e: Exception) {
                // try next
            }
        }
        return null
    }
}

fun sha1(s: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
}
