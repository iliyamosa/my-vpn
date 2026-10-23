package app.vpn.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.vpn.client.data.AccountManager
import app.vpn.client.data.Analytics
import app.vpn.client.data.ClientStore
import app.vpn.client.data.Realtime
import app.vpn.client.data.ServerRepository
import app.vpn.client.ui.ClientApp
import app.vpn.ui.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {

    /** True while the activity is in the foreground (needed before starting the VPN service). */
    val resumed = MutableStateFlow(false)

    private var vpnPermissionCallback: ((Boolean) -> Unit)? = null
    private var photoCallback: ((Uri?) -> Unit)? = null

    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val cb = photoCallback
            photoCallback = null
            cb?.invoke(uri)
        }

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
        AccountManager.loadCache(this)
        Realtime.start(this)
        if (savedInstanceState == null) Analytics.track(this, "open")
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
        AppScope.foreground.value = true
    }

    override fun onPause() {
        resumed.value = false
        AppScope.foreground.value = false
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

    /**
     * Lets the user pick ONE photo via the system picker and returns it as a small JPEG data URL.
     * Only the selected image is ever shared with the app — no access to the gallery.
     */
    fun pickAvatar(onResult: (String?) -> Unit) {
        photoCallback = cb@{ uri ->
            if (uri == null) {
                onResult(null); return@cb
            }
            try {
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (raw == null) {
                    onResult(null); return@cb
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: run { onResult(null); return@cb }
                val max = 512
                val scale = minOf(max.toFloat() / bmp.width, max.toFloat() / bmp.height, 1f)
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                onResult("data:image/jpeg;base64,$b64")
            } catch (e: Throwable) {
                onResult(null)
            }
        }
        try {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            photoCallback = null
            onResult(null)
        }
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
