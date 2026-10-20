package app.vpn.client.vpn

import kotlinx.coroutines.flow.MutableStateFlow

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/** Live VPN state shared between the service and the UI (same process). */
object VpnState {
    val status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val serverName = MutableStateFlow("")
    val serverKey = MutableStateFlow("")
    val connectedAt = MutableStateFlow(0L)
    val upSpeed = MutableStateFlow(0L)
    val downSpeed = MutableStateFlow(0L)
    val totalUp = MutableStateFlow(0L)
    val totalDown = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)

    fun resetTraffic() {
        upSpeed.value = 0
        downSpeed.value = 0
        totalUp.value = 0
        totalDown.value = 0
    }
}
