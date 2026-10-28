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
            val viaTunnel = Http.tunnelSocksPort > 0
            val curated = AccountApi.notices(base(), viaTunnel = viaTunnel)
            // Instant admin broadcasts are a separate backend channel, but shown in the same bell.
            val pushed = try { AccountApi.broadcasts(base(), viaTunnel = viaTunnel) } catch (e: Exception) { emptyList() }
            val merged = (pushed + curated)
                .distinctBy { it.id }
                .sortedByDescending { it.created }
            notices.value = merged
            val newest = merged.maxOfOrNull { it.created } ?: 0L
            unread.value = newest > ClientStore(context).lastSeenNoticeAt
            // Fire a real system notification for brand-new admin broadcasts (push-like).
            maybeNotify(context, pushed)
        } catch (e: Exception) {
            // keep what we have
        }
    }

    /** Posts device notifications for admin broadcasts newer than the last one we showed. */
    private fun maybeNotify(context: Context, broadcasts: List<Notice>) {
        val store = ClientStore(context)
        val newest = broadcasts.maxOfOrNull { it.created } ?: 0L
        val last = store.lastNotifiedAt
        if (last == 0L) {
            // First run: treat everything already on the server as delivered (don't spam).
            if (newest > 0L) store.lastNotifiedAt = newest
            return
        }
        val fresh = broadcasts.filter { it.created > last }.sortedBy { it.created }.takeLast(5)
        fresh.forEach { n ->
            AppNotifications.show(context, n.title, n.body, id = 7000 + (n.created % 100000).toInt())
        }
        if (newest > last) store.lastNotifiedAt = newest
    }

    fun markSeen(context: Context) {
        val newest = notices.value.maxOfOrNull { it.created } ?: System.currentTimeMillis()
        ClientStore(context).lastSeenNoticeAt = maxOf(newest, ClientStore(context).lastSeenNoticeAt)
        unread.value = false
    }
}
