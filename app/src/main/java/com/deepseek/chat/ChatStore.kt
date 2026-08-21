package com.deepseek.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChatStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "chat_history.json")

    fun load(ctx: Context): MutableList<Msg> {
        val out = mutableListOf<Msg>()
        return try {
            val f = file(ctx)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Msg(o.getString("role"), o.getString("content")))
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    fun save(ctx: Context, msgs: List<Msg>) {
        try {
            val arr = JSONArray()
            for (m in msgs) {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (_: Exception) {
        }
    }
}
