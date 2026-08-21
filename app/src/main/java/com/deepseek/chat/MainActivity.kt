package com.deepseek.chat

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("dsprefs", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private val history = mutableListOf<Msg>()
    private val uiItems = mutableListOf<Item>()

    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText

    private var activeCall: okhttp3.Call? = null
    private var busy = false

    class Item(val type: String, val text: StringBuilder)

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
                    tv.text = "💭 Thinking…\n${item.text}"
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
                    tv.text = item.text
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
        tv.setTextIsSelectable(true)
        return tv
    }

    private fun bubbleBg(color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = 28f }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        history.addAll(ChatStore.load(this))
        for (m in history) uiItems.add(Item(m.role, StringBuilder(m.content)))

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
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(makeInputBar())

        setContentView(root)
    }

    private fun makeTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        val title = TextView(this).apply {
            text = "DeepSeek Chat"
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
        }
        bar.addView(title, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(smallButton("New") { confirmNewChat() })
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

        bar.addView(Button(this).apply {
            text = "Send"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 32f
            }
            setOnClickListener { send() }
            setPadding(16, 8, 16, 8)
        })
        return bar
    }

    private fun confirmNewChat() {
        AlertDialog.Builder(this)
            .setTitle("New chat")
            .setMessage("Clear this conversation? It cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                activeCall?.cancel()
                busy = false
                history.clear()
                uiItems.clear()
                ChatStore.clear(this)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val model = prefs.getString("model", NviClient.DEFAULT_MODEL)?.takeIf { it.isNotBlank() }
            ?: NviClient.DEFAULT_MODEL

        busy = true
        input.setText("")

        history.add(Msg("user", text))
        ChatStore.save(this, history)
        uiItems.add(Item("user", StringBuilder(text)))
        adapter.notifyDataSetChanged()

        var thinking: StringBuilder? = null
        var content: StringBuilder? = null

        fun refresh() {
            adapter.notifyDataSetChanged()
        }

        activeCall = NviClient.stream(apiKey, model, history.toList(),
            onThinking = { chunk -> handler.post {
                if (content == null) {
                    if (thinking == null) {
                        thinking = StringBuilder()
                        uiItems.add(Item("thinking", thinking!!))
                    }
                    thinking!!.append(chunk)
                    refresh()
                }
            }},
            onContent = { chunk -> handler.post {
                if (content == null) {
                    content = StringBuilder()
                    uiItems.add(Item("assistant", content!!))
                }
                content!!.append(chunk)
                refresh()
            }},
            onDone = { err -> handler.post {
                busy = false
                if (err != null) {
                    uiItems.add(Item("error", StringBuilder(err.message ?: "Error")))
                } else if ((content == null || content!!.isBlank()) &&
                           (thinking != null && thinking!!.isNotBlank())) {
                    uiItems.add(Item("assistant", StringBuilder("(model replied with thinking only)")))
                }
                val reply = content?.toString().orEmpty().ifBlank { "" }
                if (err == null && reply.isNotEmpty()) {
                    history.add(Msg("assistant", reply))
                    ChatStore.save(this, history)
                }
                refresh()
            }}
        )
    }
}
