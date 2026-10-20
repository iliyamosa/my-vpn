package app.vpn.common

/** Data usage and expiry, read from the "subscription-userinfo" header that Marzban sends. */
data class SubscriptionInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
) {
    val used: Long get() = upload + download

    /** Expiry time in milliseconds (0 = never). */
    val expireMillis: Long get() = if (expire <= 0) 0 else if (expire > 100_000_000_000L) expire else expire * 1000

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expireMillis in 1 until now
    fun isOutOfData(): Boolean = total > 0 && used >= total
    fun daysLeft(now: Long = System.currentTimeMillis()): Int =
        if (expireMillis <= 0) -1 else ((expireMillis - now).coerceAtLeast(0) / 86_400_000L).toInt()

    companion object {
        fun parse(header: String?): SubscriptionInfo? {
            if (header.isNullOrBlank()) return null
            val map = header.split(";", ",")
                .mapNotNull { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else null
                }.toMap()
            if (map.isEmpty()) return null
            fun num(k: String) = map[k]?.toDoubleOrNull()?.toLong() ?: 0L
            return SubscriptionInfo(num("upload"), num("download"), num("total"), num("expire"))
        }
    }
}

data class SubscriptionResult(
    val servers: List<ServerProfile>,
    val info: SubscriptionInfo?,
    /** Remarks of placeholder entries (e.g. "1.5 GB / 10 GB") that panels add as fake servers. */
    val infoLines: List<String>,
    val title: String = "",
)

object SubscriptionFetcher {

    fun fetch(url: String, viaTunnel: Boolean = false, timeoutMs: Int = 15_000): SubscriptionResult {
        val r = Http.request(
            url.trim(),
            headers = mapOf("User-Agent" to Http.DEFAULT_UA, "Accept" to "*/*"),
            timeoutMs = timeoutMs,
            viaTunnel = viaTunnel,
        )
        if (!r.ok) throw HttpException("HTTP ${r.code}")
        val all = LinkParser.parseMany(r.text)
        val (info, real) = all.partition { it.isInfoOnly }
        var title = r.header("profile-title").orEmpty()
        if (title.startsWith("base64:")) title = LinkParser.decodeBase64(title.removePrefix("base64:")).orEmpty()
        return SubscriptionResult(
            servers = real,
            info = SubscriptionInfo.parse(r.header("subscription-userinfo")),
            infoLines = info.map { it.remarks }.filter { it.isNotBlank() },
            title = title,
        )
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}

/** Persian digits for nicer UI text. */
fun String.faDigits(): String {
    val sb = StringBuilder(length)
    for (c in this) {
        sb.append(if (c in '0'..'9') ('۰' + (c - '0')) else c)
    }
    return sb.toString()
}
