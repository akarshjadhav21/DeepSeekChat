package com.deepseek.chat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.deepseek.chat.engine.AppStore
import com.deepseek.chat.engine.Media
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * v3.7/v3.9 — screen capture → JPEG → vision pipeline.
 * v3.9: MediaProjection is HELD for 5 minutes after a shot, so repeat 📸 taps
 * skip the system consent dialog entirely (instant captures).
 * Flow: CaptureActivity (consent, only when not holding) → CaptureService
 * (FGS mediaProjection, required on API 34+) → frames via ImageReader.
 */
object ScreenShot {
    private var autoAsk = false
    private var question = ""

    fun launch(ctx: Context, autoAsk: Boolean, question: String) {
        this.autoAsk = autoAsk
        this.question = question
        if (CaptureService.holding) {
            // instant path — no consent needed
            ctx.startService(Intent(ctx, CaptureService::class.java)
                .setAction(CaptureService.ACTION_CAPTURE))
        } else {
            ctx.startActivity(Intent(ctx, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Single consumer of finished shots — always marshals to main thread. */
    fun consume(resultFile: File?) {
        AppStore.handler.post {
            if (resultFile == null) { toast("Screenshot failed"); return@post }
            if (autoAsk) {
                val q = question.ifBlank { "What's on this screen? Answer briefly." }
                val ok = AppStore.sendFromBubble(q, resultFile)
                if (!ok) toast("Couldn't queue — try again")
            } else {
                AppStore.pendingImages = AppStore.pendingImages + resultFile
                val model = AppStore.prefs().getString("model", NviClient.DEFAULT_MODEL) ?: ""
                toast(if (!model.contains("vision") && !model.contains("-vl"))
                    "Attached ✓ — tip: use a vision model (see 📎)"
                else "Screenshot attached ✓")
            }
        }
    }

    private fun toast(s: String) = runCatching {
        android.widget.Toast.makeText(AppStore.ctx(), s, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Translucent shim whose only job is collecting the system consent result. */
class CaptureActivity : ComponentActivity() {

    private var safety: CountDownTimer? = null

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            safety?.cancel()
            if (res.resultCode != Activity.RESULT_OK || res.data == null) {
                toast("Screen capture denied")
                finish()
                return@registerForActivityResult
            }
            startService(Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_SETUP)
                .putExtra("rc", res.resultCode)
                .putExtra("data", res.data!!))
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(0x66000000)
        val tv = TextView(this).apply {
            text = "📸 Preparing…"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(tv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        safety = object : CountDownTimer(45_000, 45_000) {
            override fun onTick(m: Long) {}
            override fun onFinish() { if (!isFinishing) finish() }
        }.start()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            consent.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            toast("Capture unavailable: ${e.message}")
            finish()
        }
    }

    override fun onDestroy() {
        safety?.cancel()
        super.onDestroy()
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}

class CaptureService : Service() {

    companion object {
        const val ACTION_SETUP = "com.deepseek.chat.capture.SETUP"
        const val ACTION_CAPTURE = "com.deepseek.chat.capture.SHOT"
        private const val HOLD_MS = 5 * 60_000L     // consent-free window
        @Volatile var holding = false
            private set
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var w = 0; private var h = 0; private var dpi = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameGate = AtomicReference<CountDownLatch?>(null)
    private val capturing = AtomicBoolean(false)
    private var holdTimeout: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> {
                if (!holding || !capturing.compareAndSet(false, true)) return START_NOT_STICKY
                captureOnce()
            }
            else -> {
                val rc = intent?.getIntExtra("rc", Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                val data = intent?.getParcelableExtra<Intent>("data")
                if (data == null || rc != Activity.RESULT_OK) {
                    ScreenShot.consume(null); stopSelf(); return START_NOT_STICKY
                }
                if (!capturing.compareAndSet(false, true)) return START_NOT_STICKY
                setup(rc, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        val chId = "capture"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(NotificationChannel(chId, "Screen capture",
                android.app.NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen capture ready — auto-stops in 5 min")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(3001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(3001, notif)
    }

    private fun setup(rc: Int, data: Intent) {
        Thread {
            var ok = false
            try {
                ensureForeground()
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(rc, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { mainHandler.post { teardownAndStop() } }
                }, mainHandler)

                val dm = resources.displayMetrics
                w = dm.widthPixels; h = dm.heightPixels; dpi = dm.densityDpi

                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                reader!!.setOnImageAvailableListener({ _ ->
                    frameGate.getAndSet(null)?.countDown()
                }, mainHandler)
                vdisplay = projection!!.createVirtualDisplay("shot", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, null)

                ok = grabFrame()
            } catch (_: Exception) {
            } finally {
                capturing.set(false)
                if (!ok) ScreenShot.consume(null)
                holding = true
                armHoldTimeout()
            }
        }.start()
    }

    private fun captureOnce() {
        Thread {
            var ok = false
            try {
                ok = grabFrame()
            } catch (_: Exception) {
            } finally {
                capturing.set(false)
                if (!ok) ScreenShot.consume(null)
                armHoldTimeout()
            }
        }.start()
    }

    /** Drains stale frames, forces a repaint, grabs one fresh frame → JPEG. */
    private fun grabFrame(): Boolean {
        val r = reader ?: return false
        runCatching { r.acquireLatestImage()?.close() }         // drain stale
        vdisplay?.resize(w, h, dpi)                             // force repaint

        val gate = CountDownLatch(1)
        frameGate.set(gate)
        val arrived = gate.await(3, TimeUnit.SECONDS)
        var img = if (arrived) r.acquireLatestImage() else null
        if (img == null) {
            Thread.sleep(200)                                   // last-chance direct grab
            img = r.acquireLatestImage() ?: return false
        }

        val plane = img.planes[0]
        val ps = plane.pixelStride
        val rowPad = plane.rowStride - ps * w
        val cap = Bitmap.createBitmap(w + rowPad / ps, h, Bitmap.Config.ARGB_8888)
        cap.copyPixelsFromBuffer(plane.buffer)
        img.close()
        val shot = Bitmap.createBitmap(cap, 0, 0, w, h)

        val raw = File(filesDir, "shot_${System.currentTimeMillis()}.jpg")
        raw.outputStream().use { shot.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        shot.recycle(); cap.recycle()
        Media.downscaleImage(raw)
        pruneOldShots()
        ScreenShot.consume(raw)
        return true
    }

    private fun pruneOldShots() {
        runCatching {
            filesDir.listFiles { f -> f.name.startsWith("shot_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(25)?.forEach { it.delete() }
        }
    }

    private fun armHoldTimeout() {
        holdTimeout?.let { mainHandler.removeCallbacks(it) }
        holdTimeout = Runnable { teardownAndStop() }
        mainHandler.postDelayed(holdTimeout!!, HOLD_MS)
    }

    private fun teardownAndStop() {
        holdTimeout?.let { mainHandler.removeCallbacks(it) }
        holding = false
        runCatching { vdisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        vdisplay = null; reader = null; projection = null
        stopSelf()
    }

    override fun onDestroy() {
        holdTimeout?.let { mainHandler.removeCallbacks(it) }
        holding = false
        runCatching { vdisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        super.onDestroy()
    }
}
