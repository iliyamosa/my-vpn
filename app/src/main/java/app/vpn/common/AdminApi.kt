package app.vpn.common

import org.json.JSONArray
import org.json.JSONObject

// ---- admin data shapes ----

data class AdminAccount(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val points: Int,
    val proUntil: Long,
    val isPro: Boolean,
    val createdAt: Long,
    val devices: Int,
    val referrals: Int,
    val hasAvatar: Boolean,
)

data class AdminAccountsPage(val total: Int, val offset: Int, val accounts: List<AdminAccount>)

data class AdminNotice(
    val id: String,
    val title: String,
    val body: String,
    val link: String,
    val button: String,
    val enabled: Boolean,
    val sort: Int,
    val created: Long,
)

data class AdminPlan(
    val id: String,
    val title: String,
    val days: Int,
    val price: String,
    val pointCost: Int,
    val amountToman: Long,
    val amountUsd: Double,
    val enabled: Boolean,
    val sort: Int,
)

data class AdminMission(
    val id: String,
    val title: String,
    val body: String,
    val kind: String,
    val goal: Int,
    val link: String,
    val points: Int,
    val enabled: Boolean,
    val sort: Int,
)

data class AdminProServer(
    val id: String,
    val name: String,
    val link: String,
    val enabled: Boolean,
    val sort: Int,
)

data class PaymentSettings(
    val merchantId: String,
    val gateway: String,
    val sandbox: Boolean,
    val buyUrl: String,
    val cryptoEnabled: Boolean = false,
    val cryptoAsset: String = "",
    val cryptoAddress: String = "",
    val cryptoAddresses: String = "",
    val cryptoNote: String = "",
    val cardEnabled: Boolean = false,
    val cardNumber: String = "",
    val cardHolder: String = "",
    val cardNote: String = "",
    val beyEnabled: Boolean = false,
    val beyApiKeySet: Boolean = false,
    val beyWebhookId: String = "",
    val beyWebhookSecretSet: Boolean = false,
    // write-only (typed by admin; blank keeps the existing value)
    val beyApiKey: String = "",
    val beyWebhookSecret: String = "",
    // Google Sign-In Web client id (public, not a secret).
    val googleClientId: String = "",
    // Invite reward points (0 = invites give no points).
    val invitePointsInviter: Int = 0,
    val invitePointsInvitee: Int = 0,
)

data class CryptoOrder(
    val id: String,
    val username: String,
    val email: String,
    val planTitle: String,
    val planDays: Int,
    val asset: String,
    val txid: String,
    val method: String,
    val status: String,
    val created: Long,
    val amount: Long = 0,
    val srcLast6: String = "",
    val trackRef: String = "",
    val paidApprox: String = "",
    val cardNumber: String = "",
    val cardHolder: String = "",
    val rejectReason: String = "",
    val hasReceipt: Boolean = false,
)

data class AuditLog(
    val actor: String,
    val action: String,
    val target: String,
    val detail: String,
    val created: Long,
)

/**
 * Talks to the backend's admin endpoints. The caller passes the admin session token
 * obtained from /auth/admin-login; it authorizes every admin request.
 */
object AdminApi {

    private fun post(base: String, path: String, token: String, bodyObj: JSONObject): JSONObject {
        val r = Http.request(
            "$base$path",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"),
            body = bodyObj.toString().toByteArray(Charsets.UTF_8),
            timeoutMs = 20_000,
        )
        return parse(r)
    }

    private fun get(base: String, path: String, token: String): JSONObject {
        val r = Http.request("$base$path", headers = mapOf("Authorization" to "Bearer $token"), timeoutMs = 20_000)
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
            throw AccountException(r.code, msg)
        }
        return o
    }

    // ---- live config ----

    fun getConfig(base: String, token: String): RemoteConfig = RemoteConfig.parse(get(base, "/admin/config", token).toString())

    /** Pushes a full RemoteConfig live, bumping the revision past the current one. Returns apps reached. */
    fun putConfig(base: String, token: String, cfg: RemoteConfig): Int {
        val currentRev = try { getConfig(base, token).revision } catch (e: Exception) { cfg.revision }
        var rev = maxOf(currentRev, cfg.revision) + 1
        repeat(2) {
            val toSend = cfg.copy(revision = rev, updatedAt = System.currentTimeMillis())
            val r = Http.request(
                "$base/admin/config",
                method = "PUT",
                headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"),
                body = toSend.toJson().toString().toByteArray(Charsets.UTF_8),
                timeoutMs = 20_000,
            )
            if (r.ok) return try { JSONObject(r.text).optInt("delivered", 0) } catch (e: Exception) { 0 }
            if (r.code == 409) {
                val serverRev = try { JSONObject(r.text).optLong("revision", rev) } catch (e: Exception) { rev }
                rev = maxOf(serverRev, rev) + 1
            } else {
                parse(r) // throws with the server's message
            }
        }
        throw AccountException(409, "سرور نسخهٔ جدیدتری دارد. دوباره تلاش کنید.")
    }

    fun ping(base: String, token: String): Int = get(base, "/admin/ping", token).optInt("online", 0)

    // ---- accounts ----

    fun listAccounts(base: String, token: String, q: String, offset: Int, limit: Int = 50): AdminAccountsPage {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val o = get(base, "/admin/accounts?q=$enc&offset=$offset&limit=$limit", token)
        val arr = o.optJSONArray("accounts")
        return AdminAccountsPage(
            total = o.optInt("total"),
            offset = o.optInt("offset"),
            accounts = (0 until (arr?.length() ?: 0)).map { i ->
                val a = arr!!.getJSONObject(i)
                AdminAccount(
                    id = a.optString("id"), email = a.optString("email"), username = a.optString("username"),
                    displayName = a.optString("displayName").ifBlank { a.optString("username") },
                    points = a.optInt("points"), proUntil = a.optLong("proUntil"), isPro = a.optBoolean("isPro"),
                    createdAt = a.optLong("createdAt"), devices = a.optInt("devices"), referrals = a.optInt("referrals"),
                    hasAvatar = a.optBoolean("hasAvatar"),
                )
            },
        )
    }

    private fun accountAction(base: String, token: String, id: String, body: JSONObject): JSONObject =
        post(base, "/admin/account", token, body.put("id", id))

    fun grantDays(base: String, token: String, id: String, days: Int) =
        accountAction(base, token, id, JSONObject().put("action", "grant_days").put("days", days))

    fun addPoints(base: String, token: String, id: String, points: Int) =
        accountAction(base, token, id, JSONObject().put("action", "add_points").put("points", points))

    fun resetPassword(base: String, token: String, id: String, newPass: String) =
        accountAction(base, token, id, JSONObject().put("action", "reset_password").put("new", newPass))

    fun clearDevices(base: String, token: String, id: String) =
        accountAction(base, token, id, JSONObject().put("action", "clear_devices"))

    fun deleteAccount(base: String, token: String, id: String) =
        accountAction(base, token, id, JSONObject().put("action", "delete"))

    // ---- notices ----

    fun listNotices(base: String, token: String): List<AdminNotice> {
        val arr = get(base, "/admin/notices", token).optJSONArray("notices")
        return (0 until (arr?.length() ?: 0)).map { i ->
            val n = arr!!.getJSONObject(i)
            AdminNotice(n.optString("id"), n.optString("title"), n.optString("body"), n.optString("link"),
                n.optString("button"), n.optBoolean("enabled"), n.optInt("sort"), n.optLong("created"))
        }
    }

    fun saveNotice(base: String, token: String, n: AdminNotice): String =
        post(base, "/admin/notice", token, JSONObject()
            .put("id", n.id).put("title", n.title).put("body", n.body).put("link", n.link)
            .put("button", n.button).put("enabled", n.enabled).put("sort", n.sort)).optString("id")

    fun deleteNotice(base: String, token: String, id: String) = post(base, "/admin/notice/delete", token, JSONObject().put("id", id))

    // ---- plans ----

    fun listPlans(base: String, token: String): List<AdminPlan> {
        val arr = get(base, "/admin/plans", token).optJSONArray("plans")
        return (0 until (arr?.length() ?: 0)).map { i ->
            val p = arr!!.getJSONObject(i)
            AdminPlan(p.optString("id"), p.optString("title"), p.optInt("days"), p.optString("price"),
                p.optInt("pointCost"), p.optLong("amountToman"), p.optDouble("amountUsd", 0.0), p.optBoolean("enabled"), p.optInt("sort"))
        }
    }

    fun savePlan(base: String, token: String, p: AdminPlan): String =
        post(base, "/admin/plan", token, JSONObject()
            .put("id", p.id).put("title", p.title).put("days", p.days).put("price", p.price)
            .put("pointCost", p.pointCost).put("amountToman", p.amountToman).put("amountUsd", p.amountUsd).put("enabled", p.enabled).put("sort", p.sort)).optString("id")

    fun deletePlan(base: String, token: String, id: String) = post(base, "/admin/plan/delete", token, JSONObject().put("id", id))

    // ---- missions ----

    fun listMissions(base: String, token: String): List<AdminMission> {
        val arr = get(base, "/admin/missions", token).optJSONArray("missions")
        return (0 until (arr?.length() ?: 0)).map { i ->
            val m = arr!!.getJSONObject(i)
            AdminMission(m.optString("id"), m.optString("title"), m.optString("body"), m.optString("kind"),
                m.optInt("goal", 1), m.optString("link"), m.optInt("points"), m.optBoolean("enabled"), m.optInt("sort"))
        }
    }

    fun saveMission(base: String, token: String, m: AdminMission): String =
        post(base, "/admin/mission", token, JSONObject()
            .put("id", m.id).put("title", m.title).put("body", m.body).put("kind", m.kind)
            .put("goal", m.goal).put("link", m.link).put("points", m.points).put("enabled", m.enabled).put("sort", m.sort)).optString("id")

    fun deleteMission(base: String, token: String, id: String) = post(base, "/admin/mission/delete", token, JSONObject().put("id", id))

    // ---- pro servers ----

    fun listProServers(base: String, token: String): List<AdminProServer> {
        val arr = get(base, "/admin/pro-servers", token).optJSONArray("servers")
        return (0 until (arr?.length() ?: 0)).map { i ->
            val s = arr!!.getJSONObject(i)
            AdminProServer(s.optString("id"), s.optString("name"), s.optString("link"), s.optBoolean("enabled"), s.optInt("sort"))
        }
    }

    fun saveProServer(base: String, token: String, s: AdminProServer): String =
        post(base, "/admin/pro-server", token, JSONObject()
            .put("id", s.id).put("name", s.name).put("link", s.link).put("enabled", s.enabled).put("sort", s.sort)).optString("id")

    fun deleteProServer(base: String, token: String, id: String) = post(base, "/admin/pro-server/delete", token, JSONObject().put("id", id))

    // ---- payment settings ----

    private fun parseSettings(o: JSONObject) = PaymentSettings(
        o.optString("merchantId"), o.optString("gateway").ifBlank { "zarinpal" }, o.optBoolean("sandbox"), o.optString("buyUrl"),
        o.optBoolean("cryptoEnabled"), o.optString("cryptoAsset"), o.optString("cryptoAddress"), o.optString("cryptoAddresses"), o.optString("cryptoNote"),
        o.optBoolean("cardEnabled"), o.optString("cardNumber"), o.optString("cardHolder"), o.optString("cardNote"),
        o.optBoolean("beyEnabled"), o.optBoolean("beyApiKeySet"), o.optString("beyWebhookId"), o.optBoolean("beyWebhookSecretSet"),
        googleClientId = o.optString("googleClientId"),
        invitePointsInviter = o.optInt("invitePointsInviter"),
        invitePointsInvitee = o.optInt("invitePointsInvitee"),
    )

    fun getSettings(base: String, token: String): PaymentSettings = parseSettings(post(base, "/admin/settings", token, JSONObject()))

    fun saveSettings(base: String, token: String, s: PaymentSettings): PaymentSettings {
        val o = post(base, "/admin/settings", token, JSONObject()
            .put("cryptoEnabled", s.cryptoEnabled).put("cryptoAddresses", s.cryptoAddresses).put("cryptoNote", s.cryptoNote)
            .put("cardEnabled", s.cardEnabled).put("cardNumber", s.cardNumber).put("cardHolder", s.cardHolder).put("cardNote", s.cardNote)
            .put("googleClientId", s.googleClientId)
            .put("invitePointsInviter", s.invitePointsInviter).put("invitePointsInvitee", s.invitePointsInvitee))
        return parseSettings(o)
    }

    // ---- crypto orders ----

    fun listCryptoOrders(base: String, token: String, status: String = ""): List<CryptoOrder> {
        val q = if (status.isBlank()) "" else "?status=" + java.net.URLEncoder.encode(status, "UTF-8")
        val arr = get(base, "/admin/crypto-orders$q", token).optJSONArray("orders")
        return (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            CryptoOrder(o.optString("id"), o.optString("username"), o.optString("email"), o.optString("planTitle"),
                o.optInt("planDays"), o.optString("asset"), o.optString("txid"), o.optString("method").ifBlank { "crypto" }, o.optString("status"), o.optLong("created"),
                o.optLong("amount"), o.optString("srcLast6"), o.optString("trackRef"), o.optString("paidApprox"),
                o.optString("cardNumber"), o.optString("cardHolder"), o.optString("rejectReason"), o.optBoolean("hasReceipt"))
        }
    }

    fun decideCryptoOrder(base: String, token: String, id: String, action: String, reason: String = "") =
        post(base, "/admin/crypto-order", token, JSONObject().put("id", id).put("action", action).put("reason", reason))

    /** Fetches a submitted receipt image's raw bytes (authorized), or null if there is none. */
    fun cryptoReceipt(base: String, token: String, id: String): ByteArray? {
        val r = Http.request(
            "$base/admin/crypto-order/receipt?id=" + java.net.URLEncoder.encode(id, "UTF-8"),
            headers = mapOf("Authorization" to "Bearer $token"), timeoutMs = 20_000,
        )
        return if (r.ok) r.body else null
    }

    // ---- audit log ----

    fun auditLogs(base: String, token: String, limit: Int = 100): List<AuditLog> {
        val arr = get(base, "/admin/audit?limit=$limit", token).optJSONArray("logs")
        return (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            AuditLog(o.optString("actor"), o.optString("action"), o.optString("target"), o.optString("detail"), o.optLong("created"))
        }
    }
}
