package com.deepseek.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Msg(val role: String, val content: String,
               val images: List<String> = emptyList(), val ts: Long = 0)

data class Chat(
    val id: String,
    var title: String,
    val msgs: MutableList<Msg> = mutableListOf(),
    var pinned: Boolean = false
)

object ChatStore {

    /** Guards read-modify-write cycles across UI + WorkManager threads. */
    val ioLock = Any()

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
                        msgs.add(Msg(m.getString("role"), m.getString("content"), imgs,
                            m.optLong("ts", 0)))
                    }
                    out.add(Chat(o.getString("id"), o.getString("title"), msgs,
                        o.optBoolean("pinned", false)))
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
            file(ctx).writeText(serialize(chats))
        } catch (_: Exception) {
        }
    }

    fun serialize(chats: List<Chat>): String {
        val arr = JSONArray()
        for (c in chats) {
            val ca = JSONArray()
            for (m in c.msgs) {
                val mo = JSONObject().put("role", m.role).put("content", m.content)
                if (m.ts > 0) mo.put("ts", m.ts)
                if (m.images.isNotEmpty()) {
                    val ia = JSONArray()
                    for (img in m.images) ia.put(img)
                    mo.put("images", ia)
                }
                ca.put(mo)
            }
            val co = JSONObject()
                .put("id", c.id)
                .put("title", c.title)
                .put("msgs", ca)
            if (c.pinned) co.put("pinned", true)
            arr.put(co)
        }
        return arr.toString()
    }

    fun deserialize(text: String): MutableList<Chat>? {
        return try {
            val out = mutableListOf<Chat>()
            val arr = JSONArray(text)
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
                    msgs.add(Msg(m.getString("role"), m.getString("content"), imgs,
                        m.optLong("ts", 0)))
                }
                out.add(Chat(o.optString("id", UUID.randomUUID().toString()),
                    o.optString("title", "Imported"), msgs,
                    o.optBoolean("pinned", false)))
            }
            if (out.isEmpty()) null else out
        } catch (_: Exception) {
            null
        }
    }
}
