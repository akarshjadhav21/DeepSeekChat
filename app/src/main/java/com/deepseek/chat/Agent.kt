package com.deepseek.chat

import java.util.concurrent.TimeUnit

object Agent {

    const val MAX_STEPS = 6
    private const val TIMEOUT_S = 20L
    private const val MAX_OUT = 4000

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
- Only binaries in /system/bin or /system/xbin exist (toybox): ls cat df ps top netstat ip ping getprop dumpsys screencap date uptime id whoami printenv stat wc head tail grep sed find sleep uname vmstat nproc settings am pm input service logcat wm
- Useful recipes: battery=dumpsys battery | storage=df -h /sdcard | memory=cat /proc/meminfo | apps=pm list packages -3 | screen=screencap -p /sdcard/dcim_screen.png | display=wm size | sensors=dumpsys sensorservice | wifi=ip route | props=getprop ro.product.model
- Never assume root. Some paths need storage permission granted to this app.
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
