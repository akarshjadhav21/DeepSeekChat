package com.deepseek.chat.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import com.deepseek.chat.*
import java.io.File

class AgentConfirm(val cmd: String, val kind: String) // kind: run|install|uninstall

object AppStore {
    val handler = Handler(Looper.getMainLooper())

    var chats by mutableStateOf<List<Chat>>(emptyList())
    var activeId by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var statusText by mutableStateOf("")
    var thinkingText by mutableStateOf<String?>(null)
    var toolText by mutableStateOf<String?>(null)
    var errorText by mutableStateOf<String?>(null)
    var liveContent by mutableStateOf<String?>(null)
    var pendingConfirm by mutableStateOf<AgentConfirm?>(null)
    var intentEvent by mutableStateOf<Intent?>(null)
    var openTalk by mutableStateOf(false)
    var openReports by mutableStateOf(false)
    var openChat by mutableStateOf(false)
    var bubbleOn by mutableStateOf(false)
    private var fromBubble by mutableStateOf(false)

    var agentOn by mutableStateOf(false)
    var agentAuto by mutableStateOf(false)
    var agentSteps by mutableStateOf(0)
        private set

    // Plan Mode: 📋 model proposes ```plan steps → user approves → checkbox execution
    var planOn by mutableStateOf(false)
    var pendingPlan by mutableStateOf<List<String>?>(null) // awaiting Approve/Discard
    var planSteps by mutableStateOf<List<Pair<String, Int>>>(emptyList()) // cmd to 0 pend 1 run 2 ok 3 fail
    var planRunning by mutableStateOf(false)

    var pendingImages by mutableStateOf<List<File>>(emptyList())
    var visionPrompt by mutableStateOf<Boolean?>(null) // true=ask switch dialog

    lateinit var prefsWrap: android.content.SharedPreferences
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        if (::prefsWrap.isInitialized) return
        appCtx = ctx.applicationContext
        prefsWrap = SecurePrefs.get(appCtx)
        reload()
    }

    fun prefs() = prefsWrap
    fun ctx() = appCtx
    val ready: Boolean get() = ::prefsWrap.isInitialized

    fun reload() {
        chats = ChatStore.list(appCtx)
        if (chats.isEmpty()) newChat()
        if (activeId == null || chats.none { it.id == activeId }) activeId = chats[0].id
    }

    fun active(): Chat? = chats.firstOrNull { it.id == activeId }
    fun history(): List<Msg> = active()?.msgs ?: emptyList()

    fun persist() {
        ChatStore.saveAll(appCtx, chats)
    }

    fun newChat(): String {
        val c = Chat(java.util.UUID.randomUUID().toString(), "New chat")
        chats = listOf(c) + chats
        activeId = c.id
        persist()
        return c.id
    }

    fun deleteChat(id: String) {
        chats = chats.filter { it.id != id }
        if (activeId == id) activeId = chats.firstOrNull()?.id
        if (chats.isEmpty()) newChat() else persist()
    }

    private fun markBusy(b: Boolean) { busy = b; if (!b) statusText = "" }

    fun stopStreaming() { activeCall?.cancel(); activeCall = null }
    private var activeCall: okhttp3.Call? = null

    // ---------- sending ----------

    fun send(text: String, modelOverride: String? = null, onNeedKey: () -> Unit) {
        if (text.isBlank() || busy || pendingImages.isNotEmpty() && text.isBlank()) return
        val apiKey = prefsWrap.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { onNeedKey(); return }

        val imgs = pendingImages
        pendingImages = emptyList()

        val chat = active() ?: return
        chat.msgs.add(Msg("user", text.trim(), imgs.map { it.absolutePath }))
        agentSteps = 0
        if (chat.title == "New chat" && chat.msgs.size >= 2) autoTitle(chat)
        persist()
        startStream(modelOverride)
    }

    /** Called by the floating bubble over other apps. Returns false if busy (text-only). */
    fun sendFromBubble(text: String, image: File? = null): Boolean {
        if (!ready || text.isBlank()) return false
        if (busy) {
            // don't drop captures mid-stream — queue for the next message
            if (image != null) {
                pendingImages = pendingImages + image
                com.deepseek.chat.AgentNotify.info(appCtx, "📸 Screenshot queued",
                    "Attached for your next message.",
                    com.deepseek.chat.AgentNotify.REPLY_ID)
                return true
            }
            return false
        }
        val key = prefsWrap.getString("api_key", "") ?: ""
        if (key.isBlank()) {
            com.deepseek.chat.AgentNotify.info(appCtx, "🔑 API key missing",
                "Open DeepSeek Chat → Settings to add your NVIDIA key.")
            return true
        }
        if (image != null) pendingImages = pendingImages + image
        fromBubble = true
        send(text) { fromBubble = false }
        return true
    }

    private fun autoTitle(chat: Chat) {        val convo = chat.msgs.takeLast(4).joinToString("\n") { "${it.role}: ${it.content.take(120)}" }
        Thread {
            try {
                val t = NviClient.complete(
                    key = prefsWrap.getString("api_key","") ?: "",
                    model = prefsWrap.getString("model", NviClient.DEFAULT_MODEL) ?: NviClient.DEFAULT_MODEL,
                    base = prefsWrap.getString("base_url", NviClient.DEFAULT_BASE) ?: NviClient.DEFAULT_BASE,
                    prompt = "Give a 3-5 word title for this conversation. Reply with ONLY the title.\n\n$convo"
                )
                if (t.isNotBlank()) handler.post {
                    chat.title = t.replace("\"","").take(40)
                    persist()
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ---------- streaming ----------

    private fun startStream(modelOverride: String? = null) {
        val apiKey = prefsWrap.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { errorText = "Set your NVIDIA API key in Settings"; return }
        val model = modelOverride?.trim()?.ifBlank { null }
            ?: prefsWrap.getString("model", NviClient.DEFAULT_MODEL)?.ifBlank { null }
            ?: NviClient.DEFAULT_MODEL
        val effort = prefsWrap.getString("effort", "high") ?: "high"
        val baseUrl = prefsWrap.getString("base_url", NviClient.DEFAULT_BASE)?.ifBlank { null } ?: NviClient.DEFAULT_BASE

        markBusy(true); thinkingText = null; toolText = null; errorText = null
        statusText = "Contacting model…"

        val msgs0 = history()
        val sysPrompt = when {
            agentOn && planOn -> Agent.PLAN_PROMPT
            agentOn -> Agent.SYSTEM_PROMPT
            else -> null
        }
        val msgs = if (sysPrompt != null) listOf(Msg("system", sysPrompt)) + msgs0 else msgs0

        var think: StringBuilder? = null
        var content: StringBuilder? = null

        activeCall = NviClient.stream(apiKey, model, msgs, effort,
            onThinking = { chunk -> handler.post {
                statusText = ""
                if (content == null) {
                    if (think == null) think = StringBuilder()
                    think!!.append(chunk)
                    thinkingText = think.toString()
                }
            }},
            onContent = { chunk -> handler.post {
                statusText = ""
                if (content == null) content = StringBuilder()
                content!!.append(chunk)
                liveContent = content.toString()
            }},
            onConnected = { handler.post { statusText = "Connected — waiting for tokens…" } },
            baseUrl = baseUrl,
            onDone = { err -> handler.post {
                val stopped = err?.message == NviClient.STOP
                markBusy(false); statusText = ""; liveContent = null
                val chat = active()
                val reply = content?.toString().orEmpty()
                if (err != null && !stopped) {
                    fromBubble = false
                    errorText = err.message ?: "Error"
                } else if (reply.isNotBlank() && chat != null) {
                    chat.msgs.add(Msg("assistant", reply))
                    persist()
                }
                if (agentOn && err == null && !stopped && reply.isNotBlank()) {
                    if (planOn) maybeTakePlan(reply) else maybeRunAgentCmd(reply)
                }
                if (fromBubble && err == null && !stopped && reply.isNotBlank()) {
                    // notify only on FINAL answers, not intermediate tool-call turns
                    val toolTurn = agentOn &&
                        (Agent.extractCmd(reply) != null || Agent.extractPlan(reply).isNotEmpty())
                    if (!toolTurn) {
                        fromBubble = false
                        com.deepseek.chat.AgentNotify.info(appCtx, "💬 Reply ready",
                            reply.replace(Regex("```[\\s\\S]*?```"), " … ").take(200),
                            com.deepseek.chat.AgentNotify.REPLY_ID)
                    }
                }
            }}
        )
    }

    // ---------- agent ----------

    private fun feedToolOutput(text: String) {
        agentSteps++
        val chat = active() ?: return
        chat.msgs.add(Msg("user", text))
        persist()
        startStream()
    }

    private fun maybeRunAgentCmd(reply: String) {
        val cmd = Agent.extractCmd(reply) ?: return
        if (agentSteps >= Agent.MAX_STEPS) {
            errorText = "Agent stopped — ${Agent.MAX_STEPS}-command limit reached."
            return
        }
        when {
            cmd.startsWith("app-uninstall ") -> {
                intentEvent = Intent(Intent.ACTION_DELETE, Uri.parse(
                    "package:" + cmd.removePrefix("app-uninstall ").trim()))
                feedToolOutput("[TOOL OUTPUT]\nSystem uninstall dialog opened. Ask the user what happened, then continue.")
            }
            cmd.startsWith("app-install ") -> {
                val path = cmd.removePrefix("app-install ").trim().removeSurrounding("\"")
                intentEvent = Intent(Intent.ACTION_VIEW).apply {
                    val f = File(path)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        appCtx, appCtx.packageName + ".fileprovider", f)
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                feedToolOutput("[TOOL OUTPUT]\nInstaller opened for $path. Ask whether it installed, then continue.")
            }
            Agent.isBlocked(cmd) -> feedToolOutput(
                "[TOOL OUTPUT]\nBLOCKED for safety: $cmd\nExplain why and suggest a safe alternative.")
            else -> {
                val confirm = AgentConfirm(cmd, "run")
                if (agentAuto) execAgentRun(cmd) else {
                    pendingConfirm = confirm
                    com.deepseek.chat.AgentNotify.needsApproval(appCtx,
                        "The agent wants to run:\n$cmd")
                }
            }
        }
    }

    fun approvePending() {
        val c = pendingConfirm ?: return
        pendingConfirm = null
        com.deepseek.chat.AgentNotify.clear(appCtx)
        execAgentRun(c.cmd)
    }

    fun denyPending() {
        val c = pendingConfirm ?: return
        pendingConfirm = null
        com.deepseek.chat.AgentNotify.clear(appCtx)
        feedToolOutput("[TOOL OUTPUT]\nUser DENIED: ${c.cmd}\nAsk what they'd like instead.")
    }

    private fun execAgentRun(cmd: String) {
        toolText = "$ $cmd\n⏳ running…"
        Thread {
            var fireIntent: Intent? = null
            val out = when {
                cmd.startsWith("ui ") -> {
                    val o = com.deepseek.chat.UiActions.execute(cmd.removePrefix("ui "))
                    if (o.startsWith("[error] Accessibility"))
                        fireIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    o
                }
                cmd.startsWith("intent ") -> {
                    val r = com.deepseek.chat.DeviceActions.run(cmd.removePrefix("intent "), appCtx)
                    fireIntent = r.second
                    r.first
                }
                else -> Agent.execute(cmd)
            }
            handler.post {
                toolText = "$ $cmd\n${out.take(Agent.MAX_OUT)}"
                fireIntent?.let { intentEvent = it }
                feedToolOutput("[TOOL OUTPUT for `$cmd`]\n${out.take(Agent.MAX_OUT)}\n" +
                    "Continue with the next command or write your final answer.")
            }
        }.start()
    }

    // ---------- plan mode ----------

    private var planGen = 0

    private fun maybeTakePlan(reply: String) {
        val steps = Agent.extractPlan(reply)
        if (steps.isEmpty()) return
        val capped = steps.take(Agent.MAX_PLAN_STEPS)
        pendingPlan = capped
        planSteps = capped.map { it to 0 }
        com.deepseek.chat.AgentNotify.needsApproval(appCtx,
            "Plan ready: ${capped.size} steps to approve")
    }

    fun approvePlan() {
        val steps = pendingPlan ?: return
        if (planRunning) return
        pendingPlan = null
        planRunning = true
        com.deepseek.chat.AgentNotify.clear(appCtx)
        val gen = ++planGen
        Thread {
            val outputs = StringBuilder()
            for ((i, cmd) in steps.withIndex()) {
                handler.post {
                    if (gen == planGen) planSteps =
                        planSteps.mapIndexed { j, p -> if (j == i) cmd to 1 else p }
                }
                val out: String = when {
                    Agent.isBlocked(cmd) -> "[BLOCKED for safety]"
                    cmd.startsWith("app-uninstall ") -> {
                        intentEvent = Intent(Intent.ACTION_DELETE, Uri.parse(
                            "package:" + cmd.removePrefix("app-uninstall ").trim()))
                        "(system uninstall dialog opened)"
                    }
                    cmd.startsWith("app-install ") -> {
                        try {
                            val path = cmd.removePrefix("app-install ").trim().removeSurrounding("\"")
                            val f = File(path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                appCtx, appCtx.packageName + ".fileprovider", f)
                            intentEvent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            "(installer opened for $path)"
                        } catch (e: Exception) {
                            "[error] ${e.message}"
                        }
                    }
                    cmd.startsWith("ui ") -> {
                        val o = com.deepseek.chat.UiActions.execute(cmd.removePrefix("ui "))
                        if (o.startsWith("[error] Accessibility"))
                            intentEvent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        o
                    }
                    cmd.startsWith("intent ") -> {
                        val r = com.deepseek.chat.DeviceActions.run(cmd.removePrefix("intent "), appCtx)
                        r.second?.let { intentEvent = it }
                        r.first
                    }
                    else -> Agent.execute(cmd)
                }
                val failed = out.startsWith("[BLOCKED") || out.startsWith("[error") ||
                    out.startsWith("[timeout")
                handler.post {
                    if (gen == planGen) planSteps =
                        planSteps.mapIndexed { j, p -> if (j == i) cmd to (if (failed) 3 else 2) else p }
                }
                outputs.append("$ ").append(cmd).append('\n').append(out).append("\n\n")
            }
            handler.post {
                if (gen != planGen) return@post
                planRunning = false
                feedToolOutput(
                    "[PLAN EXECUTED — all steps done]\n${outputs.toString().take(Agent.MAX_OUT * 2)}\n" +
                        "Summarize the results for the user in plain text.")
            }
        }.start()
    }

    fun discardPlan() {
        planGen++
        pendingPlan = null
        planSteps = emptyList()
        planRunning = false
        com.deepseek.chat.AgentNotify.clear(appCtx)
    }
}
