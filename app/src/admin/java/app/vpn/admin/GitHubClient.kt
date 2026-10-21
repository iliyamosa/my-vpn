package app.vpn.admin

import android.util.Base64
import app.vpn.common.ConfigSource
import app.vpn.common.Http
import app.vpn.common.HttpException
import app.vpn.common.RemoteConfig
import org.json.JSONObject

/** Reads and writes files in the GitHub repo through the GitHub REST API. */
class GitHubClient(private val token: String, val repo: String, val branch: String) {

    companion object {
        /** Classic token page with the needed scope already ticked. */
        const val NEW_TOKEN_URL = "https://github.com/settings/tokens/new?scopes=public_repo&description=FAIROSVPN%20Admin"

        const val READ_ONLY_HELP = "این کلید اجازه‌ی نوشتن در مخزن را ندارد. " +
            "دکمه‌ی «ساخت کلید در GitHub» را بزنید و یک کلید کلاسیک (شروع با ghp_) با تیک public_repo بسازید."
    }

    class LoadedConfig(val config: RemoteConfig, val sha: String?)

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "vpn-admin-app",
    )

    private fun api(path: String) = "https://api.github.com/repos/$repo/$path"

    private fun errorMessage(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optString("message", "")
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            401 -> "کلید GitHub نامعتبر است یا منقضی شده."
            403 -> "$READ_ONLY_HELP ($msg)"
            404 -> "مخزن پیدا نشد یا کلید به آن دسترسی ندارد. نام مخزن را بررسی کنید."
            409 -> "فایل همزمان جای دیگری تغییر کرده. دوباره بارگذاری کنید."
            422 -> "GitHub درخواست را نپذیرفت. ($msg)"
            else -> "خطای GitHub ($code) $msg"
        }
    }

    /** Checks that the token can write to the repo. Returns an error message or null. */
    fun checkAccess(): String? {
        val r = Http.request(api("").trimEnd('/'), headers = headers(), timeoutMs = 15_000)
        if (!r.ok) return errorMessage(r.code, r.text)
        val perms = JSONObject(r.text).optJSONObject("permissions")
        if (perms != null && !perms.optBoolean("push", false) && !perms.optBoolean("admin", false)) {
            return READ_ONLY_HELP
        }
        return null
    }

    fun loadConfig(): LoadedConfig {
        val r = Http.request(
            api("contents/${ConfigSource.CONFIG_PATH}?ref=$branch&t=${System.currentTimeMillis()}"),
            headers = headers(),
            timeoutMs = 15_000,
        )
        if (r.code == 404) return LoadedConfig(RemoteConfig(), null)
        if (!r.ok) throw HttpException(errorMessage(r.code, r.text))
        val o = JSONObject(r.text)
        val content = o.optString("content", "").replace("\n", "")
        val text = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        return LoadedConfig(RemoteConfig.parse(text), o.optString("sha", "").ifBlank { null })
    }

    /** Saves the config; returns the new file sha. */
    fun saveConfig(config: RemoteConfig, sha: String?): String {
        val text = config.toJsonString() + "\n"
        return putFile(ConfigSource.CONFIG_PATH, text.toByteArray(Charsets.UTF_8), sha, "Update app settings (revision ${config.revision})")
    }

    /** Uploads an image for an ad and returns a public URL for it. */
    fun uploadAdImage(bytes: ByteArray, ext: String): String {
        val name = "remote/ads/ad_${System.currentTimeMillis()}.$ext"
        putFile(name, bytes, null, "Add ad image")
        return "https://cdn.jsdelivr.net/gh/$repo@$branch/$name"
    }

    private fun putFile(path: String, bytes: ByteArray, sha: String?, message: String): String {
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        val r = Http.request(
            api("contents/$path"),
            method = "PUT",
            headers = headers() + ("Content-Type" to "application/json"),
            body = body.toString().toByteArray(Charsets.UTF_8),
            timeoutMs = 30_000,
        )
        if (!r.ok) throw HttpException(errorMessage(r.code, r.text))
        return JSONObject(r.text).optJSONObject("content")?.optString("sha", "").orEmpty()
    }

    /** Asks jsDelivr to drop its cached copy so phones get the new settings sooner. */
    fun purgeCdn() {
        val urls = if (repo.isBlank()) emptyList() else listOf(
            "https://purge.jsdelivr.net/gh/$repo@$branch/${ConfigSource.CONFIG_PATH}",
        )
        urls.forEach { url ->
            try {
                Http.request(url, timeoutMs = 10_000)
            } catch (e: Exception) {
                // not important
            }
        }
    }
}
