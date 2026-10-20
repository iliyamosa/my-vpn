package app.vpn.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.vpn.client.data.ClientStore
import app.vpn.client.data.ServerRepository
import app.vpn.client.ui.ClientApp
import app.vpn.ui.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** True while the activity is in the foreground (needed before starting the VPN service). */
    val resumed = MutableStateFlow(false)

    private var vpnPermissionCallback: ((Boolean) -> Unit)? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = vpnPermissionCallback
            vpnPermissionCallback = null
            cb?.invoke(result.resultCode == RESULT_OK)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServerRepository.loadCache(this)
        askNotificationPermissionOnce()
        setContent {
            AppTheme {
                ClientApp(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.value = true
    }

    override fun onPause() {
        resumed.value = false
        super.onPause()
    }

    fun ensureVpnPermission(onResult: (Boolean) -> Unit) {
        val intent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            null
        }
        if (intent == null) {
            onResult(true)
            return
        }
        vpnPermissionCallback = onResult
        try {
            vpnPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            vpnPermissionCallback = null
            onResult(false)
        }
    }

    fun openLink(url: String) {
        if (url.isBlank()) return
        try {
            val fixed = if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("tg://")) url else "https://$url"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("برنامه‌ای برای باز کردن لینک پیدا نشد.")
        }
    }

    fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val store = ClientStore(this)
        if (store.askedNotificationPermission) return
        store.askedNotificationPermission = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
