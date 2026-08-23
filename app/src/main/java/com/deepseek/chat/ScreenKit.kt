package com.deepseek.chat

import android.app.Activity
import android.app.NotificationChannel
import android.app.Notification
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
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.deepseek.chat.engine.AppStore
import com.deepseek.chat.engine.Media
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * v3.7 — one-shot screen capture → JPEG file → existing vision pipeline.
 * Flow: CaptureActivity (consent dialog) → CaptureService (FGS mediaProjection,
 * required on API 34+) → single frame via ImageReader → teardown immediately.
 */
object ScreenShot {
    const val EXTRA_AUTO_ASK = "auto_ask"
    private var delivered: File? = null
    private var latch: CountDownLatch? = null

    /** Service calls this when the frame is ready (or failed). */
    fun deliver(f: File?) {
        delivered = f
        latch?.countDown()
    }

    /** Activity waits here after starting the service. */
    fun awaitResult(): File? {
        delivered = null
        val l = CountDownLatch(1)
        latch = l
        l.await(9, java.util.concurrent.TimeUnit.SECONDS)
        return delivered
    }

    fun launch(ctx: Context, autoAsk: Boolean, question: String) {
        ctx.startActivity(Intent(ctx, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_AUTO_ASK, autoAsk)
            putExtra("q", question)
        })
    }
}

class CaptureActivity : ComponentActivity() {

    private var safety: android.os.CountDownTimer? = null

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            safety?.cancel()   // user answered — stop the watchdog
            if (res.resultCode != Activity.RESULT_OK || res.data == null) {
                toast("Screen capture denied")
                finish()
                return@registerForActivityResult
            }
            val i = Intent(this, CaptureService::class.java)
                .putExtra("rc", res.resultCode)
                .putExtra("data", res.data!!)
            startService(i)

            Thread {
                val f = ScreenShot.awaitResult()
                runOnUiThread { handleResult(f) }
            }.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =android.widget.FrameLayout(this)
        root.setBackgroundColor(0x66000000)
        val tv = TextView(this).apply {
            text = "📸 Capturing…"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(tv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // safety net: never hang translucent forever (generous — user must read the dialog)
        safety = object : android.os.CountDownTimer(45_000, 45_000) {
            override fun onTick(m: Long) {}
            override fun onFinish() { if (!isFinishing) finish() }
        }.start()

        try {
            consent.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            toast("Capture unavailable: ${e.message}")
            finish()
        }
    }

    private fun handleResult(file: File?) {
        val autoAsk = intent.getBooleanExtra(ScreenShot.EXTRA_AUTO_ASK, false)
        if (file == null) {
            toast("Screenshot failed")
            finish(); return
        }
        if (autoAsk) {
            val q = intent.getStringExtra("q") ?: "What's on this screen? Answer briefly."
            val ok = AppStore.sendFromBubble(q, file)
            toast(if (ok) "🤖 Analyzing screenshot…" else "Busy — try again")
        } else {
            AppStore.pendingImages = AppStore.pendingImages + file
            val model = AppStore.prefs().getString("model", NviClient.DEFAULT_MODEL) ?: ""
            if (!model.contains("vision") && !model.contains("-vl"))
                toast("Attached ✓ — tip: switch to a vision model (see 📎)")
            else toast("Screenshot attached ✓")
        }
        finish()
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rc = intent?.getIntExtra("rc", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || rc != Activity.RESULT_OK) {
            ScreenShot.deliver(null); stopSelf(); return START_NOT_STICKY
        }

        val chId = "capture"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(NotificationChannel(chId, "Screen capture",
                android.app.NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Capturing one screenshot…")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29)
            startForeground(3001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(3001, notif)

        Thread {
            var ok = false
            try {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(rc, data)
                val dm = resources.displayMetrics
                val w = dm.widthPixels; val h = dm.heightPixels

                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                val gotFrame = CountDownLatch(1)
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                reader!!.setOnImageAvailableListener({ _ ->
                    gotFrame.countDown()
                }, mainHandler)

                vdisplay = projection!!.createVirtualDisplay("shot", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, null)

                // wait for first frame (content change triggers repaint)
                gotFrame.await(4, java.util.concurrent.TimeUnit.SECONDS)
                Thread.sleep(250) // let UI settle one beat

                val img = reader!!.acquireLatestImage()
                if (img != null) {
                    val plane = img.planes[0]
                    val ps = plane.pixelStride
                    val rowPad = plane.rowStride - ps * w
                    val cap = Bitmap.createBitmap(w + rowPad / ps, h, Bitmap.Config.ARGB_8888)
                    cap.copyPixelsFromBuffer(plane.buffer)
                    img.close()
                    val shot = Bitmap.createBitmap(cap, 0, 0, w, h)

                    val raw = File(filesDir, "shot_raw_${System.currentTimeMillis()}.jpg")
                    raw.outputStream().use { shot.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    shot.recycle(); cap.recycle()
                    ok = true
                    Media.downscaleImage(raw)
                    ScreenShot.deliver(raw)
                }
            } catch (_: Exception) {
            } finally {
                if (!ok) ScreenShot.deliver(null)
                runCatching { vdisplay?.release() }
                runCatching { reader?.close() }
                runCatching { projection?.stop() }
                // prune old captures — keep newest 25 so storage can't leak
                runCatching {
                    filesDir.listFiles { f -> f.name.startsWith("shot_") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(25)?.forEach { it.delete() }
                }
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}
