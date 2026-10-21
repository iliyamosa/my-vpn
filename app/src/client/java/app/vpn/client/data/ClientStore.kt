package app.vpn.client.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.io.File

/** Small key/value + file storage for the public app. */
class ClientStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("client", Context.MODE_PRIVATE)

    var selectedKey: String
        get() = prefs.getString("selected_key", AUTO).orEmpty().ifBlank { AUTO }
        set(v) = prefs.edit().putString("selected_key", v).apply()

    var proLink: String
        get() = prefs.getString("pro_link", "").orEmpty()
        set(v) = prefs.edit().putString("pro_link", v).apply()

    /** -1 = follow admin default, 0 = off, 1 = on */
    var bypassIranMode: Int
        get() = prefs.getInt("bypass_iran", -1)
        set(v) = prefs.edit().putInt("bypass_iran", v).apply()

    var dismissedNoticeId: String
        get() = prefs.getString("dismissed_notice", "").orEmpty()
        set(v) = prefs.edit().putString("dismissed_notice", v).apply()

    var lastLink: String
        get() = prefs.getString("last_link", "").orEmpty()
        set(v) = prefs.edit().putString("last_link", v).apply()

    var lastName: String
        get() = prefs.getString("last_name", "").orEmpty()
        set(v) = prefs.edit().putString("last_name", v).apply()

    var lastBypass: Boolean
        get() = prefs.getBoolean("last_bypass", true)
        set(v) = prefs.edit().putBoolean("last_bypass", v).apply()

    /** Free-session limit of the last connection (0 = pro / unlimited). */
    var lastLimitMinutes: Int
        get() = prefs.getInt("last_limit", 0)
        set(v) = prefs.edit().putInt("last_limit", v).apply()

    var lastRatingAskAt: Long
        get() = prefs.getLong("last_rating_ask", 0L)
        set(v) = prefs.edit().putLong("last_rating_ask", v).apply()

    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean("asked_notif", false)
        set(v) = prefs.edit().putBoolean("asked_notif", v).apply()

    var proInfoHeader: String
        get() = prefs.getString("pro_info", "").orEmpty()
        set(v) = prefs.edit().putString("pro_info", v).apply()

    fun readFile(name: String): String? = try {
        val f = File(appContext.filesDir, name)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        null
    }

    fun writeFile(name: String, text: String) {
        try {
            val f = File(appContext.filesDir, name)
            val tmp = File(appContext.filesDir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun readList(name: String): List<String> = try {
        val text = readFile(name) ?: return emptyList()
        val arr = JSONArray(text)
        (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    fun writeList(name: String, list: List<String>) = writeFile(name, JSONArray(list).toString())

    companion object {
        const val AUTO = "auto"
        const val FILE_CONFIG = "remote_config.json"
        const val FILE_FREE = "free_servers.json"
        const val FILE_PRO = "pro_servers.json"
        const val FILE_PRO_INFO_LINES = "pro_info_lines.json"
    }
}
