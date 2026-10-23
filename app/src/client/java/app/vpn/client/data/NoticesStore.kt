package app.vpn.client.data

import android.content.Context
import app.vpn.common.AccountApi
import app.vpn.common.Backend
import app.vpn.common.Http
import app.vpn.common.Notice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Public notices shown to users, plus whether any are newer than the user has seen. */
object NoticesStore {
    val notices = MutableStateFlow<List<Notice>>(emptyList())
    val unread = MutableStateFlow(false)

    private fun base() = Backend.baseUrl(ServerRepository.config.value)

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        try {
            val list = AccountApi.notices(base(), viaTunnel = Http.tunnelSocksPort > 0)
            notices.value = list
            val newest = list.maxOfOrNull { it.created } ?: 0L
            unread.value = newest > ClientStore(context).lastSeenNoticeAt
        } catch (e: Exception) {
            // keep what we have
        }
    }

    fun markSeen(context: Context) {
        val newest = notices.value.maxOfOrNull { it.created } ?: System.currentTimeMillis()
        ClientStore(context).lastSeenNoticeAt = maxOf(newest, ClientStore(context).lastSeenNoticeAt)
        unread.value = false
    }
}
