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

    var agentOn by mutableStateOf(false)
    var agentAuto by mutableStateOf(false)
    private var agentSteps = 0

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

    private fun setBusy(b: Boolean) { busy = b; if (!b) statusText = "" }

    fun stopStreaming() { activeCall?.cancel(); activeCall = null }
    private var activeCall: okhttp3.Call? = null

    // ---------- sending ----------

    fun send(text: String, onNeedKey: () -> Unit) {
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
        startStream()
    }

    private fun autoTitle(chat: Chat) {
        val convo = chat.msgs.takeLast(4).joinToString("\n") { "${it.role}: ${it.content.take(120)}" }
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

    private fun startStream() {
        val apiKey = prefsWrap.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { errorText = "Set your NVIDIA API key in Settings"; return }
        val model = prefsWrap.getString("model", NviClient.DEFAULT_MODEL)?.ifBlank { null } ?: NviClient.DEFAULT_MODEL
        val effort = prefsWrap.getString("effort", "high") ?: "high"
        val baseUrl = prefsWrap.getString("base_url", NviClient.DEFAULT_BASE)?.ifBlank { null } ?: NviClient.DEFAULT_BASE

        setBusy(true); thinkingText = null; toolText = null; errorText = null
        statusText = "Contacting model…"

        val msgs0 = history()
        val msgs = if (agentOn) listOf(Msg("system", Agent.SYSTEM_PROMPT)) + msgs0 else msgs0

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
                setBusy(false); statusText = ""; liveContent = null
                val chat = active()
                val reply = content?.toString().orEmpty()
                if (err != null && !stopped) {
                    errorText = err.message ?: "Error"
                } else if (reply.isNotBlank() && chat != null) {
                    chat.msgs.add(Msg("assistant", reply))
                    persist()
                }
                if (agentOn && err == null && !stopped && reply.isNotBlank()) maybeRunAgentCmd(reply)
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
                if (agentAuto) execAgentRun(cmd) else pendingConfirm = confirm
            }
        }
    }

    fun approvePending() {
        val c = pendingConfirm ?: return
        pendingConfirm = null
        execAgentRun(c.cmd)
    }

    fun denyPending() {
        val c = pendingConfirm ?: return
        pendingConfirm = null
        feedToolOutput("[TOOL OUTPUT]\nUser DENIED: ${c.cmd}\nAsk what they'd like instead.")
    }

    private fun execAgentRun(cmd: String) {
        toolText = "$ $cmd\n⏳ running…"
        Thread {
            val out = Agent.execute(cmd)
            handler.post {
                toolText = "$ $cmd\n${out.take(Agent.MAX_OUT)}"
                feedToolOutput("[TOOL OUTPUT for `$cmd`]\n${out.take(Agent.MAX_OUT)}\n" +
                    "Continue with the next command or write your final answer.")
            }
        }.start()
    }
}
