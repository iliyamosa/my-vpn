package app.vpn.admin

import app.vpn.common.Http
import app.vpn.common.HttpException
import app.vpn.common.RemoteConfig
import org.json.JSONObject
import java.net.URLEncoder

class DayStat(
    val day: String,
    val active: Int,
    val connected: Int,
    val connects: Int,
    val fails: Int,
    val adViews: Int,
    val freeTimeouts: Int,
)

class AdStat(val id: String, val views: Int, val viewsToday: Int, val clicks: Int, val completes: Int)

class ServerStat(
    val name: String,
    val ok: Int,
    val fail: Int,
    val up: Int,
    val down: Int,
    val minutes: Int,
    val successRate: Int?,
    val satisfaction: Int?,
)

class Summary(
    val online: Int,
    val totalDevices: Int,
    val proDevices: Int,
    val active7: Int,
    val todayActive: Int,
    val todayConnected: Int,
    val satisfaction: Int?,
    val daily: List<DayStat>,
    val ads: List<AdStat>,
    val servers: List<ServerStat>,
)

class UserRow(
    val id: String,
    val model: String,
    val android: String,
    val appVersion: Int,
    val pro: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastConnect: Long,
    val connects: Int,
)

class UserPage(val total: Int, val users: List<UserRow>)

/** Talks to the Cloudflare backend. The GitHub token proves the caller is the admin. */
class BackendClient(private val token: String, val baseUrl: String) {

    val available: Boolean get() = baseUrl.startsWith("http")

    private fun headers() = mapOf(
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json",
    )

    private fun get(path: String): JSONObject {
        val r = Http.request("$baseUrl$path", headers = headers(), timeoutMs = 15_000)
        if (r.code == 401) throw HttpException("سرور مرکزی کلید GitHub را نپذیرفت.")
        if (!r.ok) throw HttpException("خطای سرور مرکزی (${r.code})")
        return JSONObject(r.text)
    }

    /** Sends the settings; every open app receives them instantly. Returns how many apps got it. */
    fun putConfig(cfg: RemoteConfig): Int {
        val r = Http.request(
            "$baseUrl/admin/config",
            method = "PUT",
            headers = headers(),
            body = cfg.toJson().toString().toByteArray(Charsets.UTF_8),
            timeoutMs = 15_000,
        )
        if (r.code == 401) throw HttpException("سرور مرکزی کلید GitHub را نپذیرفت.")
        if (!r.ok) throw HttpException("خطای سرور مرکزی (${r.code})")
        return JSONObject(r.text).optInt("delivered", 0)
    }

    /**
     * Like [putConfig], but if the live server already has a newer revision (e.g. a GitHub save failed
     * earlier), bumps the revision past it and tries again. Returns (apps reached, config actually saved).
     */
    fun putConfigWithRetry(cfg: RemoteConfig): Pair<Int, RemoteConfig> {
        var current = cfg
        repeat(2) {
            val r = Http.request(
                "$baseUrl/admin/config",
                method = "PUT",
                headers = headers(),
                body = current.toJson().toString().toByteArray(Charsets.UTF_8),
                timeoutMs = 15_000,
            )
            when {
                r.ok -> return JSONObject(r.text).optInt("delivered", 0) to current
                r.code == 409 -> {
                    val serverRev = try {
                        JSONObject(r.text).optLong("revision", current.revision)
                    } catch (e: Exception) {
                        current.revision
                    }
                    current = current.copy(revision = maxOf(serverRev, current.revision) + 1)
                }
                r.code == 401 -> throw HttpException("سرور مرکزی کلید GitHub را نپذیرفت.")
                else -> throw HttpException("خطای سرور مرکزی (${r.code})")
            }
        }
        throw HttpException("سرور مرکزی نسخه‌ی جدیدتری دارد.")
    }

    fun ping(): Int = get("/admin/ping").optInt("online", 0)

    fun summary(days: Int): Summary {
        val o = get("/admin/summary?days=$days")
        val daily = o.optJSONArray("daily")
        val ads = o.optJSONArray("ads")
        val servers = o.optJSONArray("servers")
        val today = o.optJSONObject("today")
        return Summary(
            online = o.optInt("online"),
            totalDevices = o.optInt("totalDevices"),
            proDevices = o.optInt("proDevices"),
            active7 = o.optInt("active7"),
            todayActive = today?.optInt("active") ?: 0,
            todayConnected = today?.optInt("connected") ?: 0,
            satisfaction = if (o.isNull("satisfaction")) null else o.optInt("satisfaction"),
            daily = (0 until (daily?.length() ?: 0)).map { i ->
                val d = daily!!.getJSONObject(i)
                DayStat(
                    d.optString("day"), d.optInt("active"), d.optInt("connected"),
                    d.optInt("connects"), d.optInt("fails"), d.optInt("adViews"), d.optInt("freeTimeouts"),
                )
            },
            ads = (0 until (ads?.length() ?: 0)).map { i ->
                val a = ads!!.getJSONObject(i)
                AdStat(a.optString("id"), a.optInt("views"), a.optInt("viewsToday"), a.optInt("clicks"), a.optInt("completes"))
            },
            servers = (0 until (servers?.length() ?: 0)).map { i ->
                val s = servers!!.getJSONObject(i)
                ServerStat(
                    s.optString("name"), s.optInt("ok"), s.optInt("fail"), s.optInt("up"), s.optInt("down"), s.optInt("minutes"),
                    if (s.isNull("successRate")) null else s.optInt("successRate"),
                    if (s.isNull("satisfaction")) null else s.optInt("satisfaction"),
                )
            },
        )
    }

    fun users(tier: String, offset: Int, query: String): UserPage {
        val q = URLEncoder.encode(query, "UTF-8")
        val o = get("/admin/users?tier=$tier&offset=$offset&limit=50&q=$q")
        val arr = o.optJSONArray("users")
        return UserPage(
            total = o.optInt("total"),
            users = (0 until (arr?.length() ?: 0)).map { i ->
                val u = arr!!.getJSONObject(i)
                UserRow(
                    id = u.optString("id"),
                    model = u.optString("model"),
                    android = u.optString("android"),
                    appVersion = u.optInt("appVersion"),
                    pro = u.optBoolean("pro"),
                    firstSeen = u.optLong("firstSeen"),
                    lastSeen = u.optLong("lastSeen"),
                    lastConnect = u.optLong("lastConnect"),
                    connects = u.optInt("connects"),
                )
            },
        )
    }
}
