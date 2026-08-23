package com.deepseek.chat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.deepseek.chat.engine.AppStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v3.6 "Hands phase 2" — screen control bridge.
 * The service itself is passive; all work goes through UiActions so agent verbs
 * (`ui-read`, `ui-tap "Allow"`…) can read/click OTHER apps while they are foreground.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
        val connected: Boolean get() = instance != null
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}

object UiActions {

    private const val MAX_CHARS = 3800

    /** Executes a `ui ...` verb. Safe from any thread; marshals onto main and waits. */
    fun execute(cmd: String): String {
        val svc = AgentAccessibilityService.instance
            ?: return "[error] Accessibility not enabled — tell the user: Android Settings → Accessibility → DeepSeek Chat → turn ON, then retry."
        val latch = CountDownLatch(1)
        var result = ""
        AppStore.handler.post {
            try {
                result = runVerb(svc, cmd.trim())
            } catch (e: Exception) {
                result = "[error] ${e.message}"
            } finally {
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return result.ifBlank { "[error] ui action produced no result" }
    }

    private fun runVerb(svc: AccessibilityService, cmd: String): String {
        val parts = cmd.split(Regex("\\s+"), limit = 2)
        val verb = parts[0].lowercase()
        val rest = if (parts.size > 1) parts[1].trim() else ""
        return when (verb) {
            "read" -> readScreen(svc)
            "list" -> listInteractive(svc)
            "tap" -> tapLabel(svc, rest)
            "tapxy" -> tapXY(svc, rest)
            "swipe" -> swipe(svc, rest)
            "scroll" -> scroll(svc, rest.ifBlank { "down" })
            "type" -> typeText(svc, rest)
            "back" -> global(svc, AccessibilityService.GLOBAL_ACTION_BACK, "Back")
            "home" -> global(svc, AccessibilityService.GLOBAL_ACTION_HOME, "Home")
            "recents" -> global(svc, AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")
            "notifs" -> global(svc, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notification shade")
            else -> "[error] unknown ui verb '$verb' (read|list|tap|tapxy|swipe|scroll|type|back|home|recents|notifs)"
        }
    }

    // ---------- reading ----------

    private fun readScreen(svc: AccessibilityService): String {
        val root = svc.rootInActiveWindow
            ?: return "[error] no window content available (screen may be locked or app blocked access)"
        val sb = StringBuilder("CURRENT SCREEN:\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 22 || sb.length > MAX_CHARS - 300) return
            val txt = n.text?.toString()?.trim().orEmpty()
            val cd = n.contentDescription?.toString()?.trim().orEmpty()
            if (txt.isNotEmpty() || cd.isNotEmpty()) {
                sb.append("  ".repeat(depth.coerceAtMost(6)))
                if (txt.isNotEmpty()) sb.append(txt) else sb.append("\"").append(cd).append("\"")
                if (n.isEditable) sb.append(" [input]")
                if (n.isClickable) sb.append(" ◀tap")
                sb.append('\n')
            }
            for (i in 0 until n.childCount) runCatching { walk(n.getChild(i), depth + 1) }
        }
        walk(root, 0)
        val out = sb.toString().trim()
        return if (out.length < 20) "[error] screen appears empty (secure/FLAG_SECURE apps hide their content)"
        else out.take(MAX_CHARS)
    }

    private data class Hit(val node: AccessibilityNodeInfo, val label: String, val bounds: Rect,
                            val clickable: Boolean, val scrollable: Boolean)

    private fun collect(svc: AccessibilityService): List<Hit> {
        val root = svc.rootInActiveWindow ?: return emptyList()
        val hits = mutableListOf<Hit>()
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root); seen.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 500) {
            val n = q.removeFirst(); visited++
            val r = Rect(); runCatching { n.getBoundsInScreen(r) }
            val label = n.text?.toString()?.trim().orEmpty()
                .ifBlank { n.contentDescription?.toString()?.trim().orEmpty() }
            if ((n.isClickable || n.isScrollable) && !r.isEmpty) {
                hits.add(Hit(n, label, r, n.isClickable, n.isScrollable))
            }
            for (i in 0 until n.childCount) runCatching {
                n.getChild(i)?.let { c -> if (seen.add(c)) q.add(c) }
            }
        }
        return hits
    }

    private fun listInteractive(svc: AccessibilityService): String {
        val hits = collect(svc)
        if (hits.isEmpty()) return "[error] no tappable elements found on current screen"
        val lines = hits.take(40).mapIndexed { i, h ->
            "${i + 1}. ${if (h.scrollable && !h.clickable) "SCROLLABLE" else "TAP"} '${h.label.ifBlank { "(unlabeled)" }}'" +
                " @${h.bounds.centerX()},${h.bounds.centerY()}"
        }
        return "TAPPABLE ELEMENTS:\n" + lines.joinToString("\n") +
            "\nUse: ui-tap '<label text>' or ui-tapxy <x> <y>"
    }

    // ---------- tapping ----------

    private fun tapLabel(svc: AccessibilityService, labelRaw: String): String {
        if (labelRaw.isBlank()) return "[error] ui-tap needs the button's text (see ui-list)"
        val label = labelRaw.trim().removeSurrounding("\"").lowercase()
        val hits = collect(svc)
        // best match: clickable with containing text, longest label wins ties
        val scored = hits.sortedByDescending { h ->
            val l = h.label.lowercase()
            when {
                l == label -> 4
                l.startsWith(label) -> 3
                l.contains(label) -> 2
                else -> 0
            } * 10 + h.label.length.coerceAtMost(9)
        }.filter { it.label.isNotBlank() && (it.clickable || it.scrollable) }
        val hit = scored.firstOrNull { h -> h.label.lowercase().contains(label) }
            ?: return "[error] nothing tappable matching \"$labelRaw\" — run ui-list to see options"
        if (hit.clickable && hit.node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return "Tapped '${hit.label}'."
        return gesture(svc, hit.bounds.exactCenterX(), hit.bounds.exactCenterY(), 60)
            .replace("Tapped", "Tapped '${hit.label}' via coordinates —")
    }

    private fun tapXY(svc: AccessibilityService, rest: String): String {
        val xy = rest.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
        if (xy.size < 2) return "[error] tapxy needs: ui-tapxy <x> <y> (screen pixels)"
        return gesture(svc, xy[0].toFloat(), xy[1].toFloat(), 60)
    }

    private fun swipe(svc: AccessibilityService, rest: String): String {
        val nums = rest.split(Regex("[\\s,]+")).mapNotNull { it.toIntOrNull() }
        if (nums.size < 4) return "[error] swipe needs: ui-swipe <x1> <y1> <x2> <y2> [durationMs]"
        val dur = nums.getOrElse(4) { 300 }.coerceIn(80, 3000)
        val p = Path().apply { moveTo(nums[0].toFloat(), nums[1].toFloat()); lineTo(nums[2].toFloat(), nums[3].toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur.toLong())).build()
        return if (svc.dispatchGesture(g, null, null))
            "Swiped (${nums[0]},${nums[1]}) → (${nums[2]},${nums[3]}) over ${dur}ms."
        else "[error] gesture was rejected by the system"
    }

    private fun scroll(svc: AccessibilityService, dir: String): String {
        val d = dir.lowercase()
        val forward = d == "down" || d == "right" || d == "next"
        // prefer a scrollable container's action
        collect(svc).firstOrNull { it.scrollable }?.node?.let { sNode ->
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (sNode.performAction(action)) return "Scrolled $d."
        }
        val dm = svc.resources.displayMetrics
        return when (d) {
            "up", "down" -> swipe(svc,
                "${dm.widthPixels / 2} ${(dm.heightPixels * if (forward) 0.7 else 0.3).toInt()} " +
                    "${dm.widthPixels / 2} ${(dm.heightPixels * if (forward) 0.3 else 0.7).toInt()} 250")
            else -> swipe(svc,
                "${(dm.widthPixels * if (forward) 0.7 else 0.3).toInt()} ${dm.heightPixels / 2} " +
                    "${(dm.widthPixels * if (forward) 0.3 else 0.7).toInt()} ${dm.heightPixels / 2} 250")
        }
    }

    private fun gesture(svc: AccessibilityService, x: Float, y: Float, ms: Long): String {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return if (svc.dispatchGesture(g, null, null)) "Tapped (${x.toInt()},${y.toInt()})."
        else "[error] gesture was rejected by the system"
    }

    // ---------- typing ----------

    private fun typeText(svc: AccessibilityService, text: String): String {
        if (text.isBlank()) return "[error] ui-type needs text (quote it if it has spaces)"
        val root = svc.rootInActiveWindow ?: return "[error] no window content available"
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "[error] no focused input box — use ui-tap on the text field first, then ui-type"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            "Typed into the focused field: \"${text.take(60)}\""
        else "[error] couldn't set text on the focused element"
    }

    // ---------- global ----------

    private fun global(svc: AccessibilityService, action: Int, name: String): String =
        if (svc.performGlobalAction(action)) "$name activated."
        else "[error] $name action failed"
}
