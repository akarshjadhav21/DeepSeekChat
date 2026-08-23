package com.deepseek.chat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings

/**
 * v3.5 "Hands" Phase 1 — intent toolbox.
 * Executes `intent <verb> ...` agent actions as real Android intents/UI.
 * Returns (result text for the model, Intent to fire or null).
 * Error results start with "[error]" so the plan runner marks them failed.
 */
object DeviceActions {

    fun run(action: String, ctx: Context): Pair<String, Intent?> {
        return try {
            val parts = action.trim().split(Regex("\\s+"), limit = 2)
            val verb = parts[0].lowercase()
            val rest = if (parts.size > 1) parts[1].trim() else ""
            when (verb) {
                "dial" -> dial(rest)
                "sms" -> sms(rest)
                "alarm" -> alarm(rest)
                "timer" -> timer(rest)
                "volume" -> volume(rest, ctx)
                "brightness" -> brightness(rest, ctx)
                "open" -> openApp(rest, ctx)
                "internet-panel" -> panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, ctx)
                "wifi-panel" -> panel(Settings.Panel.ACTION_WIFI, ctx)
                "bt-panel" -> btPanel(ctx)
                "dns" -> dns(rest, ctx)
                else -> "[error] unknown device action '$verb' (dial|sms|alarm|timer|volume|brightness|open|internet-panel|wifi-panel|bt-panel|dns)" to null
            }
        } catch (e: Exception) {
            "[error] ${e.message}" to null
        }
    }

    private val NEW_TASK = Intent.FLAG_ACTIVITY_NEW_TASK

    private fun dial(numRaw: String): Pair<String, Intent?> {
        val n = numRaw.filter { !it.isWhitespace() }
        if (n.isEmpty()) return "[error] dial needs a phone number" to null
        return "Dialer opened with $n typed in — user presses call." to
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(NEW_TASK)
    }

    private fun sms(rest: String): Pair<String, Intent?> {
        val seg = rest.split("|", limit = 2)
        val num = seg[0].trim()
        val body = seg.getOrElse(1) { "" }.trim()
        if (num.isEmpty()) return "[error] sms format: sms <number> | <message text>" to null
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))
            .putExtra("sms_body", body)
            .addFlags(NEW_TASK)
        val desc = if (body.isNotEmpty()) "with the message already typed" else "with no text yet"
        return "Messaging app opened for $num ($desc) — user presses send." to i
    }

    private fun alarm(rest: String): Pair<String, Intent?> {
        val m = Regex("(\\d{1,2})[:.](\\d{2})(\\s+.*)?").find(rest)
            ?: return "[error] alarm needs 24h time HH:MM (optionally followed by a label)" to null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return "[error] invalid time $h:$mi (hour 0-23, minutes 0-59)" to null
        val label = m.groupValues[3].orEmpty().trim()
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, mi)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(NEW_TASK)
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val lbl = if (label.isNotEmpty()) " \"$label\"" else ""
        return String.format("Alarm set for %02d:%02d%s.", h, mi, lbl) to i
    }

    private fun timer(rest: String): Pair<String, Intent?> {
        val seg = rest.split(Regex("\\s+"), limit = 2)
        val mins = seg[0].removeSuffix("m").toIntOrNull()
            ?: return "[error] timer needs minutes (optionally followed by a label)" to null
        if (mins <= 0 || mins > 24 * 60) return "[error] timer must be 1-1440 minutes" to null
        val label = seg.getOrElse(1) { "" }.trim()
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, mins * 60)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(NEW_TASK)
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val lbl = if (label.isNotEmpty()) " \"$label\"" else ""
        return "Timer set for ${mins}min$lbl." to i
    }

    private fun volume(argRaw: String, ctx: Context): Pair<String, Intent?> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val arg = argRaw.lowercase().ifBlank { "up" }
        when (arg) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> {
                val p = arg.removeSuffix("%").toIntOrNull()
                    ?: return "[error] volume needs up|down|mute|0-100" to null
                if (p !in 0..100) return "[error] volume percent must be 0-100" to null
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * p / 100, AudioManager.FLAG_SHOW_UI)
            }
        }
        return "Media volume now ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}." to null
    }

    private fun brightness(argRaw: String, ctx: Context): Pair<String, Intent?> {
        if (!Settings.System.canWrite(ctx)) {
            return "One-time permission needed: 'Modify system settings' screen opened — tap Allow for DeepSeek Chat, then ask me again." to
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(NEW_TASK)
        }
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val wasAuto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val target = when {
            argRaw.isBlank() || argRaw.equals("up", true) -> (cur + 51).coerceAtMost(255)
            argRaw.equals("down", true) -> (cur - 51).coerceIn(2, 255)
            else -> {
                val p = argRaw.removeSuffix("%").toIntOrNull()
                    ?: return "[error] brightness needs 0-100|up|down" to null
                if (p !in 0..100) return "[error] brightness percent must be 0-100" to null
                (255 * p / 100).coerceIn(2, 255)
            }
        }
        if (wasAuto) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        val note = if (wasAuto) " (auto-brightness switched off so it sticks)" else ""
        return "Brightness now ${target * 100 / 255}%$note." to null
    }

    private fun openApp(name: String, ctx: Context): Pair<String, Intent?> {
        if (name.isEmpty()) return "[error] open needs an app name" to null
        val pm = ctx.packageManager
        var q = name.lowercase().trim()
        // common aliases people say
        q = when (q) {
            "gallery", "photos app" -> "photos"
            "dialer", "phone app" -> "phone"
            "playstore", "play store", "market" -> "play store"
            "browser", "internet" -> "chrome"
            "messaging", "messages" -> "messages"
            else -> q
        }
        val toks = q.split(Regex("\\s+")).filter { it.length >= 2 }
        data class Cand(val info: android.content.pm.ApplicationInfo,
                        val label: String, val score: Int)

        val cands = mutableListOf<Cand>()
        for (info in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull() ?: continue
            val l = label.lowercase()
            val pkg = info.packageName.lowercase()
            if (info.enabled == false) continue
            var s = 0
            if (l == q || pkg == q) s = 100
            else {
                val lHit = toks.count { l.contains(it) }
                val pHit = toks.count { pkg.contains(it) }
                s = when {
                    toks.isNotEmpty() && lHit == toks.size -> 60
                    toks.isNotEmpty() && pHit == toks.size -> 50
                    else -> maxOf(lHit, pHit) * 15
                }
                if (s > 0 && l.startsWith(q)) s += 20
            }
            if (s > 0) cands.add(Cand(info, label, s))
        }
        cands.sortByDescending { it.score }
        val best = cands.firstOrNull()
            ?: return ("[error] no installed app matches '$name'. Closest installed apps: " +
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .mapNotNull { info ->
                        runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                            ?.let { info to it }
                    }
                    .filter { (_, l) -> toks.any { l.lowercase().contains(it) } }
                    .take(5).joinToString(" · ") { it.second }
                    .ifBlank { "(none)" } +
                " — retry with exactly one of those names.") to null
        val li = pm.getLaunchIntentForPackage(best.info.packageName)
            ?: return "[error] '${best.label}' has no launchable screen" to null
        li.addFlags(NEW_TASK)
        return "Opened ${best.label} (${best.info.packageName})." to li
    }

    private fun score0(a: String, b: String): Int = if (a.contains(b)) 1 else 0

    private fun panel(action: String, ctx: Context): Pair<String, Intent?> {
        return if (Build.VERSION.SDK_INT >= 29) {
            "Quick panel opened — toggle it there." to Intent(action).addFlags(NEW_TASK)
        } else {
            "Panels unsupported on this Android version — opened full settings page instead." to
                Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(NEW_TASK)
        }
    }

    private fun btPanel(ctx: Context): Pair<String, Intent?> =
        "Bluetooth settings opened — toggle it there." to
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(NEW_TASK)

    private fun dns(host: String, ctx: Context): Pair<String, Intent?> {
        if (host.isEmpty()) return "[error] dns needs a hostname like dns.adguard-dns.com, or 'off'" to null
        val coach = if (host.equals("off", true))
            "Network page opened — tap Private DNS → select Off."
        else
            "Network page opened — tap Private DNS → 'Private DNS provider hostname' → type exactly: $host"
        return coach to Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(NEW_TASK)
    }
}
