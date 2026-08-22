package com.deepseek.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Msg(val role: String, val content: String, val images: List<String> = emptyList())

data class Chat(
    val id: String,
    var title: String,
    val msgs: MutableList<Msg> = mutableListOf()
)

object ChatStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "chats.json")
    private fun legacyFile(ctx: Context): File = File(ctx.filesDir, "chat_history.json")

    fun list(ctx: Context): MutableList<Chat> {
        val out = mutableListOf<Chat>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val msgs = mutableListOf<Msg>()
                    val ma = o.optJSONArray("msgs") ?: JSONArray()
                    for (j in 0 until ma.length()) {
                        val m = ma.getJSONObject(j)
                        val imgs = mutableListOf<String>()
                        m.optJSONArray("images")?.let { ia ->
                            for (k in 0 until ia.length()) imgs.add(ia.getString(k))
                        }
                        msgs.add(Msg(m.getString("role"), m.getString("content"), imgs))
                    }
                    out.add(Chat(o.getString("id"), o.getString("title"), msgs))
                }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) importLegacy(ctx)?.let { out.add(it) }
        return out
    }

    private fun importLegacy(ctx: Context): Chat? {
        return try {
            val f = legacyFile(ctx)
            if (!f.exists()) return null
            val arr = JSONArray(f.readText())
            val msgs = mutableListOf<Msg>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                msgs.add(Msg(o.getString("role"), o.getString("content")))
            }
            if (msgs.isEmpty()) return null
            legacyFile(ctx).renameTo(File(ctx.filesDir, "chat_history.json.bak"))
            Chat(UUID.randomUUID().toString(), guessTitle(msgs), msgs)
        } catch (_: Exception) {
            null
        }
    }

    fun guessTitle(msgs: List<Msg>): String {
        val first = msgs.firstOrNull { it.role == "user" }?.content ?: "New chat"
        val clean = first.replace("\n", " ").trim()
        return if (clean.length > 28) clean.take(28) + "…" else clean.ifBlank { "New chat" }
    }

    fun saveAll(ctx: Context, chats: List<Chat>) {
        try {
            val arr = JSONArray()
            for (c in chats) {
                val ca = JSONArray()
                for (m in c.msgs) {
                    val mo = JSONObject().put("role", m.role).put("content", m.content)
                    if (m.images.isNotEmpty()) {
                        val ia = JSONArray()
                        for (img in m.images) ia.put(img)
                        mo.put("images", ia)
                    }
                    ca.put(mo)
                }
                arr.put(JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("msgs", ca))
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
