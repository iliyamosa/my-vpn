package app.vpn.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.vpn.BuildConfig
import app.vpn.ui.AppTheme
import java.io.ByteArrayOutputStream

class AdminStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("admin", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(v) = prefs.edit().putString("token", v).apply()

    var repo: String
        get() = prefs.getString("repo", "").orEmpty().ifBlank { BuildConfig.CONFIG_REPO }
        set(v) = prefs.edit().putString("repo", v).apply()

    var branch: String
        get() = prefs.getString("branch", "").orEmpty().ifBlank { BuildConfig.CONFIG_BRANCH.ifBlank { "main" } }
        set(v) = prefs.edit().putString("branch", v).apply()
}

class AdminActivity : ComponentActivity() {

    private var imageCallback: ((Uri?) -> Unit)? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = imageCallback
        imageCallback = null
        cb?.invoke(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AdminApp(this)
            }
        }
    }

    fun pickImage(onPicked: (Uri?) -> Unit) = pickMedia("image/*", onPicked)

    fun pickVideo(onPicked: (Uri?) -> Unit) = pickMedia("video/*", onPicked)

    private fun pickMedia(mime: String, onPicked: (Uri?) -> Unit) {
        imageCallback = onPicked
        try {
            imagePicker.launch(mime)
        } catch (e: Exception) {
            imageCallback = null
            onPicked(null)
        }
    }

    /** Reads a picked video; returns null with a message when it is too big. */
    fun readVideoForUpload(uri: Uri, maxBytes: Int): Pair<ByteArray?, String?> = try {
        val size = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
        if (size > maxBytes) {
            null to "حجم ویدیو ${size / 1024 / 1024} مگابایت است؛ حداکثر ${maxBytes / 1024 / 1024} مگابایت مجاز است. ویدیوی کوتاه‌تر یا فشرده‌تر انتخاب کنید."
        } else {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IllegalStateException("too big")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            bytes to null
        }
    } catch (e: IllegalStateException) {
        null to "حجم ویدیو بیشتر از ${maxBytes / 1024 / 1024} مگابایت است."
    } catch (e: Throwable) {
        null to "خواندن ویدیو ممکن نشد."
    }

    /** Reads, shrinks (max 1080px wide) and compresses a picked image to JPEG. */
    fun readImageForUpload(uri: Uri): ByteArray? = try {
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (raw == null) {
            null
        } else {
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (bmp == null) {
                null
            } else {
                val scaled = if (bmp.width > 1080) {
                    val h = (bmp.height * (1080f / bmp.width)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bmp, 1080, h, true)
                } else {
                    bmp
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
    } catch (e: Throwable) {
        null
    }

    fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    fun copy(text: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("text", text))
            toast("کپی شد")
        } catch (e: Exception) {
            // ignore
        }
    }

    fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("باز کردن لینک ممکن نشد.")
        }
    }
}
