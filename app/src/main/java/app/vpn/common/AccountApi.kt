package app.vpn.common

import org.json.JSONArray
import org.json.JSONObject

// ---- data shapes returned by the backend ----

data class Account(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val points: Int,
    val proUntil: Long,
    val isPro: Boolean,
    val hasAvatar: Boolean,
    val avatarPreset: Int = 0,
    val invitedBy: String = "",
) {
    val refCode: String get() = id

    companion object {
        fun from(o: JSONObject): Account = Account(
            id = o.optString("id"),
            email = o.optString("email"),
            username = o.optString("username"),
            displayName = o.optString("displayName").ifBlank { o.optString("username") },
            points = o.optInt("points"),
            proUntil = o.optLong("proUntil"),
            isPro = o.optBoolean("isPro"),
            hasAvatar = o.optBoolean("hasAvatar"),
            avatarPreset = o.optInt("avatarPreset"),
            invitedBy = o.optString("invitedBy"),
        )
    }
}

data class DeviceInfo(val id: String, val label: String, val lastSeen: Long)

data class Mission(
    val id: String,
    val title: String,
    val body: String,
    val kind: String,
    val link: String,
    val points: Int,
    val goal: Int,
    val progress: Int,
    val claimed: Boolean,
    val ready: Boolean,
)

data class Plan(val id: String, val title: String, val days: Int, val price: String, val pointCost: Int, val amountToman: Long)

data class ProServer(val name: String, val link: String)

data class Notice(val id: String, val title: String, val body: String, val link: String, val button: String, val created: Long)

data class MeResult(
    val account: Account,
    val devices: List<DeviceInfo>,
    val referrals: Int,
    val missions: List<Mission>,
    val proShowcase: List<String>,
    val proServers: List<ProServer>,
)

/** A failure from the backend: carries the HTTP status and the friendly Persian message. */
class AccountException(val status: Int, message: String, val deviceLimit: List<DeviceInfo>? = null) : Exception(message)

object AccountApi {

    private fun post(base: String, path: String, bodyObj: JSONObject, token: String? = null, viaTunnel: Boolean = false): JSONObject {
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (!token.isNullOrBlank()) headers["Authorization"] = "Bearer $token"
        val r = Http.request(
            "$base$path",
            method = "POST",
            headers = headers,
            body = bodyObj.toString().toByteArray(Charsets.UTF_8),
            timeoutMs = 15_000,
            viaTunnel = viaTunnel,
        )
        return parse(r)
    }

    private fun get(base: String, path: String, token: String? = null, viaTunnel: Boolean = false): JSONObject {
        val headers = mutableMapOf<String, String>()
        if (!token.isNullOrBlank()) headers["Authorization"] = "Bearer $token"
        val r = Http.request("$base$path", headers = headers, timeoutMs = 15_000, viaTunnel = viaTunnel)
        return parse(r)
    }

    private fun parse(r: HttpResult): JSONObject {
        val o = try {
            JSONObject(r.text)
        } catch (e: Exception) {
            if (r.ok) JSONObject() else throw AccountException(r.code, "ارتباط با سرور برقرار نشد (${r.code}).")
        }
        if (!r.ok) {
            val msg = o.optString("message").ifBlank { o.optString("error") }.ifBlank { "خطای سرور (${r.code})." }
            val devices = if (o.has("devices")) o.optJSONArray("devices").toDevices() else null
            throw AccountException(r.code, friendly(msg), devices)
        }
        return o
    }

    private fun friendly(msg: String): String = when (msg) {
        "device_limit" -> "این حساب روی ۲ دستگاه فعال است."
        else -> msg
    }

    // ---- auth ----

    class Auth(val token: String, val account: Account, val isNew: Boolean = false, val welcomeDays: Int = 0)
    private fun authFrom(o: JSONObject) = Auth(o.optString("token"), Account.from(o.getJSONObject("account")), o.optBoolean("isNew"), o.optInt("welcomeDays"))

    fun register(base: String, email: String, username: String, password: String, displayName: String, deviceId: String, model: String, ref: String): Auth {
        val o = post(base, "/auth/register", JSONObject()
            .put("email", email).put("username", username).put("password", password)
            .put("displayName", displayName).put("deviceId", deviceId).put("model", model).put("ref", ref))
        return authFrom(o)
    }

    fun login(base: String, login: String, password: String, deviceId: String, model: String): Auth {
        val o = post(base, "/auth/login", JSONObject()
            .put("login", login).put("password", password).put("deviceId", deviceId).put("model", model))
        return authFrom(o)
    }

    /** The admin session plus a real user session (so the panel also has user features). */
    class AdminAuth(val adminToken: String, val userToken: String, val account: Account?)
    fun adminLogin(base: String, username: String, password: String, deviceId: String, model: String): AdminAuth {
        val o = post(base, "/auth/admin-login", JSONObject()
            .put("username", username).put("password", password).put("deviceId", deviceId).put("model", model))
        val accObj = o.optJSONObject("account")
        return AdminAuth(o.optString("token"), o.optString("userToken"), accObj?.let { Account.from(it) })
    }

    /** The backend's configured Google Web client id (empty when Google Sign-In isn't set up). */
    fun googleConfig(base: String): String = get(base, "/auth/google-config").optString("clientId")

    /** Signs in with a Google ID token; the backend verifies it and links/creates the account. */
    fun googleLogin(base: String, idToken: String, deviceId: String, model: String, ref: String = ""): Auth {
        val o = post(base, "/auth/google", JSONObject()
            .put("idToken", idToken).put("deviceId", deviceId).put("model", model).put("ref", ref))
        return authFrom(o)
    }

    /** Redeems an invite code after sign-up (welcome screen). Returns the user's new point total. */
    fun redeemInvite(base: String, token: String, code: String, deviceId: String): Int {
        val o = post(base, "/me/redeem-invite", JSONObject().put("code", code).put("deviceId", deviceId), token)
        return o.optInt("points")
    }

    /** Saves the chosen preset avatar index on the account. */
    fun setAvatarPreset(base: String, token: String, preset: Int) {
        post(base, "/me/avatar-preset", JSONObject().put("preset", preset), token)
    }

    fun logout(base: String, token: String) {
        try {
            post(base, "/auth/logout", JSONObject(), token)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun me(base: String, token: String, viaTunnel: Boolean = false): MeResult {
        val o = get(base, "/me", token, viaTunnel)
        return MeResult(
            account = Account.from(o.getJSONObject("account")),
            devices = o.optJSONArray("devices").toDevices(),
            referrals = o.optInt("referrals"),
            missions = parseMissions(o.optJSONArray("missions")),
            proShowcase = o.optJSONArray("proShowcase").toStringList(),
            proServers = parseProServers(o.optJSONArray("proServers")),
        )
    }

    fun setDisplayName(base: String, token: String, name: String) = post(base, "/me/display-name", JSONObject().put("name", name), token)
    fun setAvatar(base: String, token: String, dataUrl: String) = post(base, "/me/avatar", JSONObject().put("data", dataUrl), token)
    fun changePassword(base: String, token: String, old: String, new: String) = post(base, "/me/password", JSONObject().put("old", old).put("new", new), token)
    fun removeDevice(base: String, token: String, deviceId: String) = post(base, "/me/device/remove", JSONObject().put("deviceId", deviceId), token)
    fun claimMission(base: String, token: String, missionId: String, confirm: Boolean) =
        post(base, "/me/mission/claim", JSONObject().put("missionId", missionId).put("confirm", confirm), token)
    fun redeem(base: String, token: String, planId: String): JSONObject = post(base, "/me/redeem", JSONObject().put("planId", planId), token)

    fun notices(base: String, viaTunnel: Boolean = false): List<Notice> =
        parseNotices(get(base, "/notices", null, viaTunnel).optJSONArray("notices"))
    /** Instant admin→users broadcasts (separate from the curated notices, shown in the same bell). */
    fun broadcasts(base: String, viaTunnel: Boolean = false): List<Notice> =
        parseNotices(get(base, "/notifications/broadcasts", null, viaTunnel).optJSONArray("notices"))
    fun plans(base: String, viaTunnel: Boolean = false): List<Plan> =
        parsePlans(get(base, "/plans", null, viaTunnel).optJSONArray("plans"))

    // ---- parsers ----

    private fun parseMissions(arr: JSONArray?): List<Mission> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Mission(
                id = m.optString("id"), title = m.optString("title"), body = m.optString("body"),
                kind = m.optString("kind"), link = m.optString("link"), points = m.optInt("points"),
                goal = m.optInt("goal", 1), progress = m.optInt("progress"), claimed = m.optBoolean("claimed"), ready = m.optBoolean("ready"),
            )
        }
    }

    private fun parseProServers(arr: JSONArray?): List<ProServer> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val link = o.optString("link")
            if (link.isBlank()) null else ProServer(o.optString("name"), link)
        }
    }

    private fun parsePlans(arr: JSONArray?): List<Plan> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Plan(p.optString("id"), p.optString("title"), p.optInt("days"), p.optString("price"), p.optInt("pointCost"), p.optLong("amountToman"))
        }
    }

    private fun parseNotices(arr: JSONArray?): List<Notice> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val n = arr.getJSONObject(i)
            Notice(n.optString("id"), n.optString("title"), n.optString("body"), n.optString("link"), n.optString("button"), n.optLong("created"))
        }
    }
}

private fun JSONArray?.toDevices(): List<DeviceInfo> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        val o = optJSONObject(i) ?: return@mapNotNull null
        DeviceInfo(o.optString("id"), o.optString("label").ifBlank { "دستگاه" }, o.optLong("lastSeen"))
    }
}
