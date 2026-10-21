package app.vpn.client.vpn

import kotlinx.coroutines.flow.MutableStateFlow

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

enum class EndReason { USER, FREE_LIMIT, ERROR, SYSTEM }

/** How a connection ended; used to ask the user for a rating. */
data class SessionEnd(val server: String, val minutes: Int, val reason: EndReason, val at: Long = System.currentTimeMillis())

/** Live VPN state shared between the service and the UI (same process). */
object VpnState {
    const val PING_TESTING = -2L
    const val PING_FAILED = -1L

    val status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val serverName = MutableStateFlow("")
    val serverKey = MutableStateFlow("")
    val connectedAt = MutableStateFlow(0L)

    /** When a free session will be cut (0 = no limit). */
    val sessionDeadline = MutableStateFlow(0L)

    /** Real delay through the connected server. */
    val pingMs = MutableStateFlow(PING_TESTING)

    val upSpeed = MutableStateFlow(0L)
    val downSpeed = MutableStateFlow(0L)
    val totalUp = MutableStateFlow(0L)
    val totalDown = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    val lastSessionEnd = MutableStateFlow<SessionEnd?>(null)

    fun resetTraffic() {
        upSpeed.value = 0
        downSpeed.value = 0
        totalUp.value = 0
        totalDown.value = 0
    }
}
