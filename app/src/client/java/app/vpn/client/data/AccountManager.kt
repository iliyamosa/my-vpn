package app.vpn.client.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import app.vpn.common.Account
import app.vpn.common.AccountApi
import app.vpn.common.AccountException
import app.vpn.common.Backend
import app.vpn.common.Http
import app.vpn.common.MeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Holds the logged-in account and talks to the backend account API. */
object AccountManager {
    val account = MutableStateFlow<Account?>(null)
    val me = MutableStateFlow<MeResult?>(null)
    val loggedIn = MutableStateFlow(false)
    val isAdmin = MutableStateFlow(false)
    /** The user's own avatar, decoded for display (null = show the default glyph). */
    val avatar = MutableStateFlow<Bitmap?>(null)

    @Volatile private var loaded = false

    private fun base() = Backend.baseUrl(ServerRepository.config.value)
    private fun model() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60)

    fun loadCache(context: Context) {
        if (loaded) return
        loaded = true
        val store = ClientStore(context)
        loggedIn.value = store.authToken.isNotBlank()
        isAdmin.value = store.adminToken.isNotBlank()
        val j = store.accountJson
        if (j.isNotBlank()) {
            try {
                account.value = Account.from(JSONObject(j))
            } catch (e: Exception) {
                // ignore
            }
        }
        avatar.value = decodeDataUrl(store.avatarData)
    }

    /** Decodes a `data:image/...;base64,...` string to a Bitmap, or null. */
    private fun decodeDataUrl(dataUrl: String): Bitmap? {
        if (dataUrl.isBlank()) return null
        return try {
            val comma = dataUrl.indexOf(',')
            val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /** Uploads a newly picked avatar (a JPEG data URL), updates the local display and the server. */
    suspend fun uploadAvatar(context: Context, dataUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val store = ClientStore(context)
        val token = store.authToken
        if (token.isBlank()) return@withContext Result.failure(Exception("ابتدا وارد شوید."))
        try {
            AccountApi.setAvatar(base(), token, dataUrl)
            store.avatarData = dataUrl
            avatar.value = decodeDataUrl(dataUrl)
            refreshMe(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setDisplayName(context: Context, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = ClientStore(context).authToken
        if (token.isBlank()) return@withContext Result.failure(Exception("ابتدا وارد شوید."))
        try {
            AccountApi.setDisplayName(base(), token, name.trim())
            refreshMe(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun changePassword(context: Context, old: String, new: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = ClientStore(context).authToken
        if (token.isBlank()) return@withContext Result.failure(Exception("ابتدا وارد شوید."))
        try {
            AccountApi.changePassword(base(), token, old, new)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun claimMission(context: Context, missionId: String, confirm: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        val token = ClientStore(context).authToken
        if (token.isBlank()) return@withContext Result.failure(Exception("ابتدا وارد شوید."))
        try {
            val o = AccountApi.claimMission(base(), token, missionId, confirm)
            refreshMe(context)
            Result.success(o.optInt("awarded"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeDevice(context: Context, deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = ClientStore(context).authToken
        if (token.isBlank()) return@withContext Result.failure(Exception("ابتدا وارد شوید."))
        try {
            AccountApi.removeDevice(base(), token, deviceId)
            refreshMe(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun redeem(context: Context, planId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = ClientStore(context).authToken
        if (token.isBlank()) return@withContext Result.failure(Exception("ابتدا وارد شوید."))
        try {
            AccountApi.redeem(base(), token, planId)
            refreshMe(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cache(context: Context, acc: Account) {
        account.value = acc
        ClientStore(context).accountJson = JSONObject()
            .put("id", acc.id).put("email", acc.email).put("username", acc.username)
            .put("displayName", acc.displayName).put("points", acc.points)
            .put("proUntil", acc.proUntil).put("isPro", acc.isPro).put("hasAvatar", acc.hasAvatar)
            .toString()
    }

    suspend fun register(context: Context, email: String, username: String, password: String, displayName: String, ref: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val store = ClientStore(context)
            try {
                val auth = AccountApi.register(base(), email.trim(), username.trim(), password, displayName.trim(), store.deviceId, model(), ref.trim())
                store.authToken = auth.token
                cache(context, auth.account)
                loggedIn.value = true
                refreshMe(context)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun login(context: Context, login: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val store = ClientStore(context)
            try {
                val auth = AccountApi.login(base(), login.trim(), password, store.deviceId, model())
                store.authToken = auth.token
                cache(context, auth.account)
                loggedIn.value = true
                refreshMe(context)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun refreshMe(context: Context) = withContext(Dispatchers.IO) {
        val store = ClientStore(context)
        val token = store.authToken
        if (token.isBlank()) return@withContext
        try {
            val r = AccountApi.me(base(), token, viaTunnel = Http.tunnelSocksPort > 0)
            me.value = r
            cache(context, r.account)
            loggedIn.value = true
            // Hand the pro server links to the repository so the home list can use them.
            ServerRepository.setProServers(context, r.proServers, r.proShowcase, r.account.isPro)
        } catch (e: AccountException) {
            if (e.status == 401) {
                // token no longer valid (logged out elsewhere / device removed)
                logoutLocal(context)
            }
        } catch (e: Exception) {
            // network error: keep cached state
        }
    }

    suspend fun logout(context: Context) = withContext(Dispatchers.IO) {
        val store = ClientStore(context)
        val token = store.authToken
        if (token.isNotBlank()) AccountApi.logout(base(), token)
        logoutLocal(context)
    }

    private fun logoutLocal(context: Context) {
        val showcase = me.value?.proShowcase ?: emptyList()
        ClientStore(context).clearSession()
        account.value = null
        me.value = null
        avatar.value = null
        loggedIn.value = false
        ServerRepository.setProServers(context, emptyList(), showcase, false)
    }

    suspend fun adminLogin(context: Context, username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = AccountApi.adminLogin(base(), username, password)
            if (token.isBlank()) return@withContext Result.failure(Exception("ورود ناموفق بود."))
            ClientStore(context).adminToken = token
            isAdmin.value = true
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun adminLogout(context: Context) {
        ClientStore(context).adminToken = ""
        isAdmin.value = false
    }
}
