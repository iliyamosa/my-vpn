package app.vpn.common

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the admin app controls. Stored as remote/config.json in the GitHub repo,
 * downloaded by the public app and cached on the phone.
 */
data class RemoteConfig(
    val revision: Long = 0,
    val updatedAt: Long = 0,
    val free: FreeConfig = FreeConfig(),
    val pro: ProConfig = ProConfig(),
    val ads: AdsConfig = AdsConfig(),
    val notice: NoticeConfig = NoticeConfig(),
    val update: UpdateConfig = UpdateConfig(),
    val support: SupportConfig = SupportConfig(),
    val testUrl: String = DEFAULT_TEST_URL,
    val mirrors: List<String> = emptyList(),
    val bypassIranDefault: Boolean = true,
    /** Address of the live backend (Cloudflare Worker). Empty = use the one built into the app. */
    val backendUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("revision", revision)
        put("updatedAt", updatedAt)
        put("free", free.toJson())
        put("pro", pro.toJson())
        put("ads", ads.toJson())
        put("notice", notice.toJson())
        put("update", update.toJson())
        put("support", support.toJson())
        put("testUrl", testUrl)
        put("mirrors", JSONArray(mirrors))
        put("bypassIranDefault", bypassIranDefault)
        put("backendUrl", backendUrl)
    }

    fun toJsonString(): String = toJson().toString(2)

    companion object {
        const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

        fun parse(text: String): RemoteConfig = fromJson(JSONObject(text))

        fun fromJson(o: JSONObject): RemoteConfig = RemoteConfig(
            revision = o.optLong("revision", 0),
            updatedAt = o.optLong("updatedAt", 0),
            free = FreeConfig.fromJson(o.optJSONObject("free")),
            pro = ProConfig.fromJson(o.optJSONObject("pro")),
            ads = AdsConfig.fromJson(o.optJSONObject("ads")),
            notice = NoticeConfig.fromJson(o.optJSONObject("notice")),
            update = UpdateConfig.fromJson(o.optJSONObject("update")),
            support = SupportConfig.fromJson(o.optJSONObject("support")),
            testUrl = o.optString("testUrl", DEFAULT_TEST_URL).ifBlank { DEFAULT_TEST_URL },
            mirrors = o.optJSONArray("mirrors").toStringList(),
            bypassIranDefault = o.optBoolean("bypassIranDefault", true),
            backendUrl = o.optString("backendUrl", "").trim(),
        )
    }
}

data class ManualServer(val name: String = "", val link: String = "") {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("link", link)

    companion object {
        fun fromJson(o: JSONObject?): ManualServer =
            ManualServer(o?.optString("name").orEmpty(), o?.optString("link").orEmpty())
    }
}

data class FreeConfig(
    val enabled: Boolean = true,
    val subscriptions: List<String> = emptyList(),
    val servers: List<ManualServer> = emptyList(),
    val hideKeywords: List<String> = emptyList(),
    /** Free users are disconnected after this many minutes (0 = no limit). */
    val sessionMinutes: Int = 60,
    /**
     * Copy of the server links behind the subscriptions, saved by the admin app.
     * Used when a phone cannot reach the subscription address.
     */
    val snapshot: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("sessionMinutes", sessionMinutes)
        put("snapshot", JSONArray(snapshot))
        put("subscriptions", JSONArray(subscriptions))
        put("servers", JSONArray().also { arr -> servers.forEach { arr.put(it.toJson()) } })
        put("hideKeywords", JSONArray(hideKeywords))
    }

    companion object {
        fun fromJson(o: JSONObject?): FreeConfig {
            if (o == null) return FreeConfig()
            val list = mutableListOf<ManualServer>()
            val arr = o.optJSONArray("servers")
            if (arr != null) for (i in 0 until arr.length()) list.add(ManualServer.fromJson(arr.optJSONObject(i)))
            return FreeConfig(
                enabled = o.optBoolean("enabled", true),
                subscriptions = o.optJSONArray("subscriptions").toStringList(),
                servers = list.filter { it.link.isNotBlank() },
                hideKeywords = o.optJSONArray("hideKeywords").toStringList(),
                sessionMinutes = o.optInt("sessionMinutes", 60).coerceIn(0, 24 * 60),
                snapshot = o.optJSONArray("snapshot").toStringList(),
            )
        }
    }
}

data class ProConfig(
    val enabled: Boolean = true,
    val title: String = "اشتراک پرو",
    val description: String = "سرعت بیشتر، لوکیشن‌های بیشتر و بدون تبلیغ",
    val priceText: String = "",
    val buyText: String = "خرید اشتراک",
    val buyLink: String = "",
    val showcase: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("title", title)
        put("description", description)
        put("priceText", priceText)
        put("buyText", buyText)
        put("buyLink", buyLink)
        put("showcase", JSONArray(showcase))
    }

    companion object {
        fun fromJson(o: JSONObject?): ProConfig {
            if (o == null) return ProConfig()
            val d = ProConfig()
            return ProConfig(
                enabled = o.optBoolean("enabled", true),
                title = o.optString("title", d.title),
                description = o.optString("description", d.description),
                priceText = o.optString("priceText", ""),
                buyText = o.optString("buyText", d.buyText),
                buyLink = o.optString("buyLink", ""),
                showcase = o.optJSONArray("showcase").toStringList(),
            )
        }
    }
}

data class AdItem(
    val id: String = "",
    val enabled: Boolean = true,
    val title: String = "",
    val text: String = "",
    val imageUrl: String = "",
    val videoUrl: String = "",
    val link: String = "",
    val buttonText: String = "مشاهده",
    val seconds: Int = 5,
    val weight: Int = 1,
    // Where it shows: "interstitial" (full screen before connecting) or "home" (banner on the home screen).
    val placement: String = "interstitial",
    // Auto-expiry: "none", "time" (hidden after expiresAt), or "views" (hidden once views >= maxViews).
    val limitKind: String = "none",
    val expiresAt: Long = 0L,
    val maxViews: Int = 0,
    // Server-provided, read-only: how many times this ad has been viewed (all time).
    val views: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("title", title)
        put("text", text)
        put("imageUrl", imageUrl)
        put("videoUrl", videoUrl)
        put("link", link)
        put("buttonText", buttonText)
        put("seconds", seconds)
        put("weight", weight)
        put("placement", placement)
        put("limitKind", limitKind)
        put("expiresAt", expiresAt)
        put("maxViews", maxViews)
    }

    /** True when this ad has hit its configured limit (time passed or view cap reached). */
    fun limitReached(now: Long): Boolean = when (limitKind) {
        "time" -> expiresAt in 1 until now
        "views" -> maxViews > 0 && views >= maxViews
        else -> false
    }

    companion object {
        fun fromJson(o: JSONObject?): AdItem {
            if (o == null) return AdItem()
            return AdItem(
                id = o.optString("id", ""),
                enabled = o.optBoolean("enabled", true),
                title = o.optString("title", ""),
                text = o.optString("text", ""),
                imageUrl = o.optString("imageUrl", ""),
                videoUrl = o.optString("videoUrl", ""),
                link = o.optString("link", ""),
                buttonText = o.optString("buttonText", "مشاهده"),
                seconds = o.optInt("seconds", 5).coerceIn(0, 60),
                weight = o.optInt("weight", 1).coerceIn(1, 100),
                placement = o.optString("placement", "interstitial").ifBlank { "interstitial" },
                limitKind = o.optString("limitKind", "none").ifBlank { "none" },
                expiresAt = o.optLong("expiresAt", 0L),
                maxViews = o.optInt("maxViews", 0),
                views = o.optInt("views", 0),
            )
        }
    }
}

data class AdsConfig(
    val enabled: Boolean = false,
    val showForPro: Boolean = false,
    val items: List<AdItem> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("showForPro", showForPro)
        put("items", JSONArray().also { arr -> items.forEach { arr.put(it.toJson()) } })
    }

    /** Active ads for a placement: enabled, has content, and not past their time/view limit. */
    fun activeItems(placement: String = "interstitial", now: Long = System.currentTimeMillis()): List<AdItem> =
        items.filter {
            it.enabled &&
                (it.placement.ifBlank { "interstitial" } == placement) &&
                !it.limitReached(now) &&
                (it.title.isNotBlank() || it.text.isNotBlank() || it.imageUrl.isNotBlank() || it.videoUrl.isNotBlank())
        }

    companion object {
        fun fromJson(o: JSONObject?): AdsConfig {
            if (o == null) return AdsConfig()
            val list = mutableListOf<AdItem>()
            val arr = o.optJSONArray("items")
            if (arr != null) for (i in 0 until arr.length()) list.add(AdItem.fromJson(arr.optJSONObject(i)))
            return AdsConfig(
                enabled = o.optBoolean("enabled", false),
                showForPro = o.optBoolean("showForPro", false),
                items = list,
            )
        }
    }
}

data class NoticeConfig(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val buttonText: String = "",
    val link: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("id", id)
        put("title", title)
        put("message", message)
        put("buttonText", buttonText)
        put("link", link)
    }

    companion object {
        fun fromJson(o: JSONObject?): NoticeConfig {
            if (o == null) return NoticeConfig()
            return NoticeConfig(
                enabled = o.optBoolean("enabled", false),
                id = o.optString("id", ""),
                title = o.optString("title", ""),
                message = o.optString("message", ""),
                buttonText = o.optString("buttonText", ""),
                link = o.optString("link", ""),
            )
        }
    }
}

data class UpdateConfig(
    val latestVersionCode: Int = 0,
    val minVersionCode: Int = 0,
    val url: String = "",
    val message: String = "نسخه‌ی جدید برنامه آماده است.",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("latestVersionCode", latestVersionCode)
        put("minVersionCode", minVersionCode)
        put("url", url)
        put("message", message)
    }

    companion object {
        fun fromJson(o: JSONObject?): UpdateConfig {
            if (o == null) return UpdateConfig()
            return UpdateConfig(
                latestVersionCode = o.optInt("latestVersionCode", 0),
                minVersionCode = o.optInt("minVersionCode", 0),
                url = o.optString("url", ""),
                message = o.optString("message", UpdateConfig().message),
            )
        }
    }
}

data class SupportConfig(
    val telegram: String = "",
    val text: String = "پشتیبانی",
) {
    fun toJson(): JSONObject = JSONObject().put("telegram", telegram).put("text", text)

    companion object {
        fun fromJson(o: JSONObject?): SupportConfig {
            if (o == null) return SupportConfig()
            return SupportConfig(
                telegram = o.optString("telegram", ""),
                text = o.optString("text", "پشتیبانی"),
            )
        }
    }
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        val s = optString(i, "").trim()
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}
