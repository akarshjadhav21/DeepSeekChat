package com.deepseek.chat

import java.util.concurrent.TimeUnit

object Agent {

    const val MAX_STEPS = 6
    const val MAX_PLAN_STEPS = 10
    private const val TIMEOUT_S = 20L
    const val MAX_OUT = 4000

    val SYSTEM_PROMPT = """
You are an AI agent running INSIDE an Android chat app on the user's phone.
You can execute shell commands on the phone using the app's built-in runner.

To run a command, reply with EXACTLY ONE fenced block tagged `run` and nothing else:

```run
ls /sdcard
```

Rules:
- One command per reply. The app will run it and send you the output as [TOOL OUTPUT].
- After seeing output, either run another command or write your final answer in plain text.
- Max ${MAX_STEPS} commands per task — be efficient, don't repeat commands.
- Only binaries in /system/bin or /system/xbin exist (toybox): ls cat df ps top netstat ip ping getprop dumpsys screencap date uptime id whoami printenv stat wc head tail grep sed find sleep uname vmstat nproc settings am pm input service logcat wm cmd monkey
- Useful recipes: battery=dumpsys battery | storage=df -h /sdcard | memory=cat /proc/meminfo | apps=pm list packages -3 | appinfo=dumpsys package NAME | screen=screencap -p /sdcard/dcim_screen.png | display=wm size | wifi=ip route | props=getprop ro.product.model
- You CAN also ACT on the phone — these are normal, allowed actions:
    * Launch an installed app (two steps):
        pm list packages | grep <guess>        → find the exact package name
        monkey -p <package> -c android.intent.category.LAUNCHER 1   → launch it
      If monkey fails, fall back to:
        cmd package resolve-activity --brief <package>
        am start -n <package>/<component-from-output>
    * Open a system settings screen, e.g.:
        am start -a android.settings.WIFI_SETTINGS
      Working actions include: SETTINGS, WIFI_SETTINGS, AIRPLANE_MODE_SETTINGS,
      BLUETOOTH_SETTINGS, DISPLAY_SETTINGS, SOUND_SETTINGS,
      INTERNAL_STORAGE_SETTINGS, APPLICATION_DEVELOPMENT_SETTINGS
    * Device actions (PREFER these over shell when one fits — they open real Android UI):
        intent dial <number>
        intent sms <number> | <message text>
        intent alarm <HH:MM> <optional label>     (24h clock)
        intent timer <minutes> <optional label>
        intent volume <up|down|mute|0-100>
        intent brightness <0-100|up|down>
        intent open <app name>                    -> finds & launches installed app by name
        intent internet-panel / wifi-panel / bt-panel   -> quick settings panels
        intent dns <hostname|off>                 -> opens Private DNS page; tell user exactly what to type
    * Screen control (read & tap OTHER apps live; needs user's Accessibility ON):
        ui-read                     -> text content of the current screen
        ui-list                     -> tappable elements with labels + coordinates
        ui-tap <label text>         -> tap that button/element (e.g. "Allow", "Send")
        ui-tapxy <x> <y>            -> tap exact screen coordinates
        ui-swipe <x1 y1 x2 y2 [ms]> -> swipe gesture
        ui-scroll up|down|left|right
        ui-type <text>              -> type into focused input (ui-tap the field first)
        ui-back | ui-home | ui-recents | ui-notifs
      If a ui action fails with 'Accessibility not enabled', tell the user how to enable it
      and stop that step — do not repeat it.
- HONESTY RULE: never claim you lack permission or access without first TRYING the command.
  If a command fails, quote its real error and suggest an alternative.
- Special actions (NOT shell commands, use inside a ```run block):
    app-install /path/to/file.apk     -> opens system installer
    app-uninstall com.package.name    -> opens system uninstall dialog
- Never assume root. Some paths need storage permission granted to this app.
""".trim().trimIndent()

    val PLAN_PROMPT = """
You are an AI agent running INSIDE an Android chat app on the user's phone, in PLAN MODE.
The user will give you a multi-step task. You CANNOT execute anything right now —
instead produce a plan of shell commands for the user to review and approve.

Reply with EXACTLY ONE fenced block tagged `plan` and nothing else:

```plan
dumpsys battery | grep -E "level|status"
df -h /sdcard
```

Rules:
- One shell command per line, top-to-bottom order. ${MAX_PLAN_STEPS} steps max — prefer fewer.
- Only binaries in /system/bin or /system/xbin exist (toybox): ls cat df ps top netstat ip ping getprop dumpsys screencap date uptime id whoami printenv stat wc head tail grep sed find sleep uname vmstat nproc settings am pm input service logcat wm cmd monkey
- Useful recipes: battery=dumpsys battery | storage=df -h /sdcard | memory=cat /proc/meminfo | apps=pm list packages -3 | appinfo=dumpsys package NAME | screen=screencap -p /sdcard/dcim_screen.png | display=wm size | wifi=ip route | props=getprop ro.product.model
- Acting is allowed in plans too:
    * Launch an app: pm list packages | grep <guess>, then
      monkey -p <package> -c android.intent.category.LAUNCHER 1
      (fallback: cmd package resolve-activity --brief <package> then am start -n <pkg>/<component>)
    * Open system screens: am start -a android.settings.WIFI_SETTINGS
      (also SETTINGS, AIRPLANE_MODE_SETTINGS, BLUETOOTH_SETTINGS, DISPLAY_SETTINGS,
       SOUND_SETTINGS, INTERNAL_STORAGE_SETTINGS)
    * Device actions as plan steps: intent dial <number> · intent sms <num> | <text> ·
      intent alarm HH:MM · intent timer <min> · intent volume up|down|0-100 ·
      intent brightness 0-100 · intent open <app> · intent wifi-panel / bt-panel / internet-panel ·
      intent dns <hostname|off>
    * Screen control steps: ui-read · ui-list · ui-tap <label> · ui-tapxy <x y> ·
      ui-swipe x1 y1 x2 y2 · ui-scroll down · ui-type <text> · ui-back / ui-home
- Special actions allowed as steps (NOT shell commands): app-install /path/to/file.apk · app-uninstall com.package.name
- Never assume root. Some paths need storage permission granted to this app.
- Nothing runs until the user approves the whole plan. Do not add commentary outside the block.
""".trim().trimIndent()

    private val DENY = listOf(
        "su ", "su\n", "reboot", "shutdown", "poweroff",
        "rm -rf /", "rm -rf *", "mkfs", "dd if=", "flash",
        "pm uninstall", "pm clear", "pm disable", "am force-stop",
        "settings put", "svc ", "setprop", "mount -o"
    )

    fun isBlocked(cmd: String): Boolean {
        val c = cmd.trim().lowercase()
        return DENY.any { c.contains(it) } || c.startsWith("su")
    }

    /** Extracts the first ```run fenced command from a reply, or null. */
    fun extractCmd(reply: String): String? {
        val marker = "```run"
        val i = reply.indexOf(marker)
        if (i < 0) return null
        var body = reply.substring(i + marker.length)
        if (body.startsWith("\n")) body = body.substring(1)
        val end = body.indexOf("```")
        val cmd = (if (end >= 0) body.substring(0, end) else body).trim()
        return cmd.takeIf { it.isNotEmpty() }
    }

    /** Extracts commands from the first ```plan fenced block — one per line, numbering stripped. */
    fun extractPlan(reply: String): List<String> {
        val marker = "```plan"
        val i = reply.indexOf(marker)
        if (i < 0) return emptyList()
        var body = reply.substring(i + marker.length)
        if (body.startsWith("\n")) body = body.substring(1)
        val end = body.indexOf("```")
        if (end >= 0) body = body.substring(0, end)
        return body.lines()
            .map { it.trim().replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.isNotEmpty() }
    }

    /** Runs a command under sh, captures combined output, truncates. */
    fun execute(cmd: String): String {
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            val br = proc.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + TIMEOUT_S * 1000
            val buf = CharArray(4096)
            var timedOut = false
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    proc.destroyForcibly()
                    break
                }
                if (br.ready()) {
                    val n = br.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length >= MAX_OUT) {
                        sb.append("\n…[truncated]")
                        proc.destroyForcibly()
                        break
                    }
                } else {
                    if (!proc.isAlive && !br.ready()) break
                    Thread.sleep(60)
                }
            }
            if (timedOut) sb.append("\n[timeout after ${TIMEOUT_S}s]")
            sb.toString().trim().ifBlank { "(no output)" }
        } catch (e: Exception) {
            "[error] ${e.message}"
        }
    }

    fun quoteForChat(cmd: String, out: String): String =
        "$ ${cmd}\n${out.take(MAX_OUT)}"
}
