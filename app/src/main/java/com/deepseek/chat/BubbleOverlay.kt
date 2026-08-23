package com.deepseek.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.deepseek.chat.engine.AppStore
import kotlin.math.abs

/**
 * v3.6 — draggable 🤖 bubble that floats over ANY app.
 * Tap → mini chat panel; ask a question and the AI can read/tap the app underneath.
 * Plain started service (no FGS) — overlay permission must be granted once in Settings.
 */
class BubbleService : android.app.Service() {

    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var panel: View? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        AppStore.handler.post { AppStore.bubbleOn = true }
        showBubble()
    }

    override fun onDestroy() {
        removeViews()
        AppStore.handler.post { AppStore.bubbleOn = false }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun removeViews() {
        runCatching { bubble?.let { wm?.removeView(it) } }
        runCatching { panel?.let { wm?.removeView(panel) } }
        bubble = null; panel = null
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun bubbleParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 24; p.y = 420
        return p
    }

    private fun showBubble() {
        removeViews()
        val size = (46 * resources.displayMetrics.density).toInt()
        val v = FrameLayout(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(0xFF7C4DFF.toInt(), 0xFF448AFF.toInt())
        }
        v.background = bg
        val t = TextView(this).apply {
            text = "🤖"; textSize = 20f; gravity = Gravity.CENTER
        }
        v.addView(t, FrameLayout.LayoutParams(size, size))

        val p = bubbleParams()
        var downX = 0f; var downY = 0f; var moved = false
        var px = p.x; var py = p.y
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        moved = true
                        px = (px + dx).toInt(); py = (py + dy).toInt()
                        p.x = px.coerceAtLeast(0); p.y = py.coerceAtLeast(0)
                        wm?.updateViewLayout(view, p)
                        downX = ev.rawX; downY = ev.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) expandPanel(); true }
                else -> false
            }
        }
        bubble = v
        wm?.addView(v, p)
    }

    @SuppressLint("RtlHardcoded")
    private fun panelParams(): WindowManager.LayoutParams {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.92f).toInt()
        val p = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // focusable → keyboard works
            PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.CENTER
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        return p
    }

    @SuppressLint("SetTextI18n")
    private fun expandPanel() {
        if (panel != null) return
        runCatching { bubble?.let { wm?.removeView(it) } }
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xF0101012.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val title = TextView(this).apply {
            text = "🤖 Ask over this screen"
            textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(0xFFB39DFF.toInt())
        }
        card.addView(title)

        val input = EditText(this).apply {
            hint = "What do you want to know / do here?"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0x22FFFFFF)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        card.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        fun btn(label: String, tint: Int): TextView = TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(tint)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ask = btn("▶ Ask", 0xFF7C4DFF.toInt())
        val shot = btn("📸", 0x33FFFFFF)
        val hide = btn("–", 0x33FFFFFF)
        val quit = btn("⏻", 0x33FFFFFF)

        ask.setOnClickListener {
            val q = input.text.toString().trim()
            collapseToBubble()
            if (q.isEmpty()) return@setOnClickListener
            val ok = AppStore.sendFromBubble(q)
            if (!ok) Toast.makeText(this, "Busy — try again in a moment", Toast.LENGTH_SHORT).show()
        }
        shot.setOnClickListener {
            collapseToBubble()
            ScreenShot.launch(this, autoAsk = true,
                question = "What's on this screen? Answer briefly.")
        }
        hide.setOnClickListener { collapseToBubble() }
        quit.setOnClickListener {
            stopSelf()
        }

        row.addView(ask, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(shot, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(6) })
        row.addView(hide, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(6) })
        row.addView(quit, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(6) })
        card.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val p = panelParams()
        panel = card
        wm?.addView(card, p)
        input.requestFocus()
    }

    private fun collapseToBubble() {
        runCatching { panel?.let { wm?.removeView(it) } }
        panel = null
        if (bubble == null) showBubble()
    }
}

object Bubble {
    fun start(ctx: android.content.Context): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        ctx.startService(Intent(ctx, BubbleService::class.java))
        return true
    }

    fun stop(ctx: android.content.Context) {
        ctx.stopService(Intent(ctx, BubbleService::class.java))
    }
}
