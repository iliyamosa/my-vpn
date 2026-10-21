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

    /**
     * Uploads an ad image or video (up to ~20 MB) and returns a public URL pinned to the new commit,
     * so it is served immediately and never stale. Uses the Git data API, which handles big files.
     */
    fun uploadMedia(bytes: ByteArray, ext: String): String {
        val path = "remote/ads/ad_${System.currentTimeMillis()}.$ext"
        val h = headers() + ("Content-Type" to "application/json")

        fun call(method: String, apiPath: String, body: JSONObject?, timeout: Int = 30_000): JSONObject {
            val r = Http.request(
                api(apiPath), method = method, headers = h,
                body = body?.toString()?.toByteArray(Charsets.UTF_8), timeoutMs = timeout,
            )
            if (!r.ok) throw HttpException(errorMessage(r.code, r.text))
            return JSONObject(r.text)
        }

        // 1. the file itself
        val blobBody = JSONObject().put("encoding", "base64").put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val blobSha = call("POST", "git/blobs", blobBody, timeout = 180_000).getString("sha")

        // 2-5. add it to a new commit on the branch (retry if the branch moved meanwhile)
        repeat(3) { attempt ->
            val head = call("GET", "git/ref/heads/$branch", null).getJSONObject("object").getString("sha")
            val baseTree = call("GET", "git/commits/$head", null).getJSONObject("tree").getString("sha")
            val tree = call(
                "POST", "git/trees",
                JSONObject().put("base_tree", baseTree).put(
                    "tree",
                    org.json.JSONArray().put(
                        JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blobSha),
                    ),
                ),
            ).getString("sha")
            val commit = call(
                "POST", "git/commits",
                JSONObject().put("message", "Add ad media").put("tree", tree).put("parents", org.json.JSONArray().put(head)),
            ).getString("sha")
            val r = Http.request(
                api("git/refs/heads/$branch"), method = "PATCH", headers = h,
                body = JSONObject().put("sha", commit).put("force", false).toString().toByteArray(Charsets.UTF_8),
                timeoutMs = 30_000,
            )
            if (r.ok) return "https://cdn.jsdelivr.net/gh/$repo@$commit/$path"
            if (r.code != 422 || attempt == 2) throw HttpException(errorMessage(r.code, r.text))
        }
        throw HttpException("آپلود انجام نشد.")
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
