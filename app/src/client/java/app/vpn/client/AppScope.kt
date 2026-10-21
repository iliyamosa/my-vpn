package app.vpn.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide coroutine scope and app state (shared by the UI and the VPN service). */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while the main screen is visible. */
    val foreground = MutableStateFlow(false)
}
