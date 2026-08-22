package com.deepseek.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {

    private val prefs by lazy { SecurePrefs.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    private val chats = mutableListOf<Chat>()
    private var activeIdx = 0
    private var history = mutableListOf<Msg>()

    private val uiItems = mutableListOf<Item>()

    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var titleView: TextView

    private var activeCall: okhttp3.Call? = null
    private var busy = false

    class Item(val type: String, val text: StringBuilder) {
        var expanded = false
    }

    inner class ChatAdapter : BaseAdapter() {
        override fun getCount(): Int = uiItems.size
        override fun getItem(position: Int): Any = uiItems[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView as? TextView ?: makeBubble()
            val item = uiItems[position]
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            when (item.type) {
                "user" -> {
                    lp.gravity = Gravity.END
                    tv.background = bubbleBg(Color.parseColor("#1565C0"))
                    tv.setTextColor(Color.WHITE)
                    tv.setTypeface(Typeface.DEFAULT)
                    tv.text = item.text
                }
                "thinking" -> {
                    lp.gravity = Gravity.START
                    tv.background = bubbleBg(Color.parseColor("#1E1E1E"))
                    tv.setTextColor(Color.parseColor("#90A4AE"))
                    tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC))
                    val full = item.text
                    tv.text = if (item.expanded || full.length < 220) {
                        "💭 Thinking…\n$full"
                    } else {
                        "💭 Thinking… (${full.length} chars) ▸ tap to expand\n" +
                            full.substring(0, 180) + "…"
                    }
                }
                "error" -> {
                    lp.gravity = Gravity.START
                    tv.background = bubbleBg(Color.parseColor("#4E2020"))
                    tv.setTextColor(Color.parseColor("#FF8A80"))
                    tv.setTypeface(Typeface.DEFAULT)
                    tv.text = "⚠ ${item.text}"
                }
                else -> {
                    lp.gravity = Gravity.START
                    tv.background = bubbleBg(Color.parseColor("#262626"))
                    tv.setTextColor(Color.WHITE)
                    tv.setTypeface(Typeface.DEFAULT)
                    tv.text = Markdown.render(item.text.toString())
                }
            }
            tv.layoutParams = lp
            return tv
        }
    }

    private fun makeBubble(): TextView {
        val tv = TextView(this)
        val pad = (14 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad / 2 + 4, pad, pad / 2 + 4)
        tv.textSize = 15f
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.80).toInt()
        tv.setTextIsSelectable(false)
        return tv
    }

    private fun bubbleBg(color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = 28f }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        chats.addAll(ChatStore.list(this))
        if (chats.isEmpty()) chats.add(Chat(UUID.randomUUID().toString(), "New chat"))
        activeIdx = chats.size - 1
        history = chats[activeIdx].msgs
        rebuildUi()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        root.addView(makeTopBar())

        adapter = ChatAdapter()
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            setStackFromBottom(true)
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(8, 8, 8, 8)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setOnItemClickListener { _, _, pos, _ ->
                val it2 = uiItems.getOrNull(pos) ?: return@setOnItemClickListener
                if (it2.type == "thinking") {
                    it2.expanded = !it2.expanded
                    this@MainActivity.adapter.notifyDataSetChanged()
                }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                showBubbleMenu(pos)
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(makeInputBar())

        setContentView(root)
        refreshTitle()
    }

    private fun rebuildUi() {
        uiItems.clear()
        for (m in history) uiItems.add(Item(m.role, StringBuilder(m.content)))
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun refreshTitle() {
        if (::titleView.isInitialized) {
            val t = chats[activeIdx].title
            titleView.text = if (t.length > 16) t.take(16) + "…" else t
        }
    }

    private fun persist() = ChatStore.saveAll(this, chats)

    private fun makeTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        titleView = TextView(this).apply {
            textSize = 17f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(smallButton("💬") { showChatsDialog() })
        bar.addView(smallButton("New") { newChat() })
        bar.addView(smallButton("⚙") { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
        return bar
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#82B1FF"))
            background = null
            setOnClickListener { onClick() }
        }

    private fun newChat() {
        stopStreaming()
        chats.add(Chat(UUID.randomUUID().toString(), "New chat"))
        activeIdx = chats.size - 1
        history = chats[activeIdx].msgs
        persist()
        rebuildUi()
        refreshTitle()
        input.setText("")
        Toast.makeText(this, "New chat ✓", Toast.LENGTH_SHORT).show()
    }

    private fun switchChat(idx: Int) {
        stopStreaming()
        activeIdx = idx
        history = chats[activeIdx].msgs
        rebuildUi()
        refreshTitle()
        input.setText("")
    }

    private fun showChatsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        val lv = ListView(this).apply {
            divider = null
        }
        val dlgTitles = chats.mapIndexed { i, c ->
            (if (i == activeIdx) "▶ " else "") + "${c.title}  ·  ${c.msgs.size} msgs"
        }
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, dlgTitles)
        container.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        var dialogRef: AlertDialog? = null
        row.addView(smallButton("＋ New") {
            dialogRef?.dismiss()
            newChat()
        })
        row.addView(smallButton("🗑 Delete") {
            dialogRef?.dismiss()
            confirmDeleteChat()
        })
        container.addView(row)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Chats (${chats.size})")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        dialogRef = dialog
        lv.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            switchChat(pos)
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt())
    }

    private fun confirmDeleteChat() {
        AlertDialog.Builder(this)
            .setTitle("Delete chat")
            .setMessage("\"${chats[activeIdx].title}\" will be deleted permanently.")
            .setPositiveButton("Delete") { _, _ ->
                stopStreaming()
                chats.removeAt(activeIdx)
                if (chats.isEmpty()) chats.add(Chat(UUID.randomUUID().toString(), "New chat"))
                activeIdx = 0
                history = chats[0].msgs
                persist()
                rebuildUi()
                refreshTitle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBubbleMenu(pos: Int) {
        val item = uiItems.getOrNull(pos) ?: return
        val opts = mutableListOf("📋 Copy")
        if (item.type == "assistant" || item.type == "user") opts.add("📤 Share")
        if (item.type == "assistant") opts.add("</> Copy code")
        val canRegen = !busy && pos == uiItems.lastIndex &&
            item.type == "assistant" && history.isNotEmpty()
        if (canRegen) opts.add("↻ Regenerate")

        AlertDialog.Builder(this)
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    "📋 Copy" -> copyText(item.text.toString())
                    "📤 Share" -> shareText(item.text.toString())
                    "</> Copy code" -> {
                        val code = Markdown.codeBlocks(item.text.toString())
                        if (code.isBlank()) Toast.makeText(this,
                            "No code block found", Toast.LENGTH_SHORT).show()
                        else copyText(code)
                    }
                    "↻ Regenerate" -> regenerate()
                }
            }
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DeepSeek", text))
        Toast.makeText(this, "Copied ✓", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "Share via"))
    }

    private fun makeInputBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        val density = resources.displayMetrics.density
        input = EditText(this).apply {
            hint = "Ask DeepSeek anything…"
            setHintTextColor(Color.parseColor("#616161"))
            setTextColor(Color.WHITE)
            maxLines = 5
            setBackgroundResource(android.R.color.transparent)
            setPadding(8, 12, 8, 12)
        }
        val inputWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1C"))
                cornerRadius = 32f
            }
        }
        inputWrap.addView(input)
        bar.addView(inputWrap, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, (8 * density).toInt(), 0) })

        sendBtn = Button(this).apply {
            text = "Send"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 32f
            }
            setPadding(16, 8, 16, 8)
        }
        sendBtn.setOnClickListener {
            if (busy) stopStreaming() else send()
        }
        bar.addView(sendBtn)
        return bar
    }

    private fun setBusyUi(b: Boolean) {
        busy = b
        sendBtn.text = if (b) "■ Stop" else "Send"
    }

    private fun stopStreaming() {
        activeCall?.cancel()
        activeCall = null
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return

        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Set your NVIDIA API key in ⚙ Settings first",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (chats[activeIdx].title == "New chat") {
            chats[activeIdx].title = ChatStore.guessTitle(listOf(Msg("user", text)))
            refreshTitle()
        }

        input.setText("")
        history.add(Msg("user", text))
        uiItems.add(Item("user", StringBuilder(text)))
        persist()
        adapter.notifyDataSetChanged()
        startStream()
    }

    private fun regenerate() {
        if (busy) return
        while (history.isNotEmpty() && history.last().role == "assistant") {
            history.removeAt(history.size - 1)
            uiItems.removeAt(uiItems.size - 1)
        }
        if (history.isEmpty() || history.last().role != "user") {
            adapter.notifyDataSetChanged()
            return
        }
        persist()
        adapter.notifyDataSetChanged()
        startStream()
    }

    private fun startStream() {
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", NviClient.DEFAULT_MODEL)?.takeIf { it.isNotBlank() }
            ?: NviClient.DEFAULT_MODEL
        val effort = prefs.getString("effort", null)?.takeIf { it.isNotBlank() } ?: "high"

        setBusyUi(true)

        var thinking: StringBuilder? = null
        var content: StringBuilder? = null

        activeCall = NviClient.stream(apiKey, model, history.toList(), effort,
            onThinking = { chunk -> handler.post {
                if (content == null) {
                    if (thinking == null) {
                        thinking = StringBuilder()
                        uiItems.add(Item("thinking", thinking!!))
                    }
                    thinking!!.append(chunk)
                    adapter.notifyDataSetChanged()
                }
            }},
            onContent = { chunk -> handler.post {
                if (content == null) {
                    content = StringBuilder()
                    uiItems.add(Item("assistant", content!!))
                }
                content!!.append(chunk)
                adapter.notifyDataSetChanged()
            }},
            onDone = { err -> handler.post {
                val stopped = err?.message == NviClient.STOP
                setBusyUi(false)
                if (err != null && !stopped) {
                    uiItems.add(Item("error", StringBuilder(err.message ?: "Error")))
                } else if ((content == null || content!!.isBlank()) &&
                           (thinking != null && thinking!!.isNotBlank())) {
                    uiItems.add(Item("assistant", StringBuilder("(model replied with thinking only)")))
                }
                val reply = content?.toString().orEmpty()
                if ((err == null || stopped) && reply.isNotBlank()) {
                    history.add(Msg("assistant", reply))
                    persist()
                }
                adapter.notifyDataSetChanged()
            }}
        )
    }
}
