package app.vpn.client.vpn

import android.content.Context
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/** Thin wrapper around the Xray core library (libv2ray.aar). */
object CoreManager {
    private const val TAG = "CoreManager"

    @Volatile
    private var initialized = false
    private var controller: CoreController? = null

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long {
            Log.i(TAG, "core: $message")
            return 0
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        Seq.setContext(context.applicationContext)
        val assetDir = File(context.filesDir, "assets").apply { mkdirs() }
        Libv2ray.initCoreEnv(assetDir.absolutePath, "")
        initialized = true
    }

    @Synchronized
    fun start(context: Context, configJson: String, tunFd: Int) {
        init(context)
        val c = controller ?: Libv2ray.newCoreController(callback).also { controller = it }
        if (c.isRunning) c.stopLoop()
        c.startLoop(configJson, tunFd)
        if (!c.isRunning) throw IllegalStateException("core did not start")
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        try {
            if (c.isRunning) c.stopLoop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
        }
    }

    fun isRunning(): Boolean = controller?.isRunning == true

    /** "tag,uplink,123;tag,downlink,456;" since the last call. */
    fun queryStats(): String = try {
        controller?.queryAllOutboundTrafficStats().orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** Real delay through a server, in ms, or -1 on failure. Works without the VPN running. */
    fun measureDelay(context: Context, testConfig: String, url: String): Long = try {
        init(context)
        Libv2ray.measureOutboundDelay(testConfig, url)
    } catch (e: Exception) {
        -1L
    }

    fun version(context: Context): String = try {
        init(context)
        Libv2ray.checkVersionX()
    } catch (e: Exception) {
        ""
    }
}
