package app.vpn.client.vpn

import app.vpn.common.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/** Turns a parsed server into a full Xray JSON config. */
object XrayConfigBuilder {
    const val MTU = 1500

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
        "169.254.0.0/16", "100.64.0.0/10", "fc00::/7", "fe80::/10",
    )

    fun buildVpnConfig(p: ServerProfile, socksPort: Int, bypassIran: Boolean, useGeoip: Boolean = true): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("stats", JSONObject())
        root.put(
            "policy", JSONObject()
                .put(
                    "levels", JSONObject().put(
                        "8", JSONObject()
                            .put("handshake", 4)
                            .put("connIdle", 300)
                            .put("uplinkOnly", 1)
                            .put("downlinkOnly", 1)
                    )
                )
                .put(
                    "system", JSONObject()
                        .put("statsOutboundUplink", true)
                        .put("statsOutboundDownlink", true)
                )
        )

        val sniffing = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
            .put("routeOnly", true)

        val inbounds = JSONArray()
        inbounds.put(
            JSONObject()
                .put("tag", "tun")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("mtu", MTU).put("userLevel", 8))
                .put("sniffing", sniffing)
        )
        inbounds.put(
            JSONObject()
                .put("tag", "socks")
                .put("listen", "127.0.0.1")
                .put("port", socksPort)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8))
        )
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(buildOutbound(p, "proxy"))
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        root.put("outbounds", outbounds)

        root.put(
            "dns", JSONObject()
                .put("servers", JSONArray(listOf("1.1.1.1", "8.8.8.8")))
                .put("queryStrategy", "UseIPv4")
        )

        val rules = JSONArray()
        rules.put(
            JSONObject().put("type", "field")
                .put("inboundTag", JSONArray(listOf("tun")))
                .put("port", "53")
                .put("outboundTag", "dns-out")
        )
        rules.put(
            JSONObject().put("type", "field")
                .put("ip", JSONArray(PRIVATE_CIDRS))
                .put("outboundTag", "direct")
        )
        if (bypassIran) {
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(listOf("domain:ir")))
                    .put("outboundTag", "direct")
            )
            if (useGeoip) {
                rules.put(
                    JSONObject().put("type", "field")
                        .put("ip", JSONArray(listOf("geoip:ir")))
                        .put("outboundTag", "direct")
                )
            }
        }
        root.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
        return root.toString()
    }

    /** Minimal config used only to measure a server's delay. */
    fun buildTestConfig(p: ServerProfile): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "none"))
        root.put("outbounds", JSONArray().put(buildOutbound(p, "proxy")))
        return root.toString()
    }

    fun buildOutbound(p: ServerProfile, tag: String): JSONObject {
        val out = JSONObject().put("tag", tag)
        val settings = JSONObject()
        when (p.protocol) {
            "vless" -> {
                out.put("protocol", "vless")
                settings.put("address", p.address)
                    .put("port", p.port)
                    .put("id", p.id)
                    .put("encryption", p.encryption.ifBlank { "none" })
                    .put("level", 8)
                if (p.flow.isNotBlank()) settings.put("flow", p.flow)
            }

            "vmess" -> {
                out.put("protocol", "vmess")
                settings.put("address", p.address)
                    .put("port", p.port)
                    .put("id", p.id)
                    .put("security", p.method.ifBlank { "auto" })
                    .put("level", 8)
            }

            "trojan" -> {
                out.put("protocol", "trojan")
                settings.put("address", p.address)
                    .put("port", p.port)
                    .put("password", p.password)
                    .put("level", 8)
                if (p.flow.isNotBlank()) settings.put("flow", p.flow)
            }

            "shadowsocks" -> {
                out.put("protocol", "shadowsocks")
                settings.put("address", p.address)
                    .put("port", p.port)
                    .put("password", p.password)
                    .put("method", p.method)
                    .put("level", 8)
            }

            else -> throw IllegalArgumentException("unsupported protocol ${p.protocol}")
        }
        out.put("settings", settings)
        out.put("streamSettings", buildStream(p))
        return out
    }

    private fun isDomain(s: String): Boolean =
        s.isNotBlank() && !s.contains(":") && !Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(s)

    private fun buildStream(p: ServerProfile): JSONObject {
        val s = JSONObject()
        val network = if (p.protocol == "shadowsocks") "tcp" else p.network.ifBlank { "tcp" }
        s.put("network", network)
        var sniFromTransport = ""
        when (network) {
            "tcp" -> {
                if (p.headerType == "http") {
                    val hosts = p.host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val paths = p.path.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("/") }
                    val headers = JSONObject()
                        .put("User-Agent", JSONArray(listOf("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")))
                        .put("Accept-Encoding", JSONArray(listOf("gzip, deflate")))
                        .put("Connection", JSONArray(listOf("keep-alive")))
                        .put("Pragma", "no-cache")
                    if (hosts.isNotEmpty()) headers.put("Host", JSONArray(hosts))
                    val request = JSONObject()
                        .put("version", "1.1")
                        .put("method", "GET")
                        .put("path", JSONArray(paths))
                        .put("headers", headers)
                    s.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", request)))
                    sniFromTransport = hosts.firstOrNull().orEmpty()
                } else {
                    sniFromTransport = p.host
                }
            }

            "ws" -> {
                s.put("wsSettings", JSONObject().put("host", p.host).put("path", p.path.ifBlank { "/" }))
                sniFromTransport = p.host
            }

            "httpupgrade" -> {
                s.put("httpupgradeSettings", JSONObject().put("host", p.host).put("path", p.path.ifBlank { "/" }))
                sniFromTransport = p.host
            }

            "xhttp", "splithttp" -> {
                s.put("network", "xhttp")
                val x = JSONObject().put("host", p.host).put("path", p.path.ifBlank { "/" })
                if (p.mode.isNotBlank()) x.put("mode", p.mode)
                if (p.extra.isNotBlank()) {
                    try {
                        x.put("extra", JSONObject(p.extra))
                    } catch (e: Exception) {
                        // ignore malformed extra
                    }
                }
                s.put("xhttpSettings", x)
                sniFromTransport = p.host
            }

            "grpc" -> {
                val g = JSONObject()
                    .put("serviceName", p.serviceName)
                    .put("multiMode", p.mode == "multi")
                    .put("idle_timeout", 60)
                    .put("health_check_timeout", 20)
                if (p.authority.isNotBlank()) g.put("authority", p.authority)
                s.put("grpcSettings", g)
                sniFromTransport = p.authority
            }

            "h2" -> {
                val hosts = p.host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                s.put("httpSettings", JSONObject().put("host", JSONArray(hosts)).put("path", p.path.ifBlank { "/" }))
                sniFromTransport = hosts.firstOrNull().orEmpty()
            }

            else -> {}
        }

        val security = p.security
        if (security == "tls" || security == "reality") {
            s.put("security", security)
            val sni = when {
                p.sni.isNotBlank() -> p.sni
                isDomain(sniFromTransport) -> sniFromTransport
                isDomain(p.address) -> p.address
                else -> sniFromTransport
            }
            val t = JSONObject()
            if (sni.isNotBlank()) t.put("serverName", sni)
            if (p.fingerprint.isNotBlank()) t.put("fingerprint", p.fingerprint)
            if (security == "tls") {
                val alpn = p.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (alpn.isNotEmpty()) t.put("alpn", JSONArray(alpn))
                if (p.insecure && p.pinnedPeerCertSha256.isBlank()) t.put("allowInsecure", true)
                if (p.echConfigList.isNotBlank()) t.put("echConfigList", p.echConfigList)
                if (p.verifyPeerCertByName.isNotBlank()) t.put("verifyPeerCertByName", p.verifyPeerCertByName)
                if (p.pinnedPeerCertSha256.isNotBlank()) t.put("pinnedPeerCertSha256", p.pinnedPeerCertSha256)
                s.put("tlsSettings", t)
            } else {
                if (p.publicKey.isNotBlank()) t.put("publicKey", p.publicKey)
                if (p.shortId.isNotBlank()) t.put("shortId", p.shortId)
                if (p.spiderX.isNotBlank()) t.put("spiderX", p.spiderX)
                if (p.mldsa65Verify.isNotBlank()) t.put("mldsa65Verify", p.mldsa65Verify)
                s.put("realitySettings", t)
            }
        }
        return s
    }
}
