package com.deepseek.chat

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Msg(val role: String, val content: String)

object NviClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    const val DEFAULT_MODEL = "deepseek-ai/deepseek-v4-flash-0731"

    fun buildBody(model: String, messages: List<Msg>, effort: String = "high"): JSONObject {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        return JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 1.0)
            .put("top_p", 0.95)
            .put("max_tokens", 16384)
            .put("chat_template_kwargs", JSONObject()
                .put("thinking", true)
                .put("reasoning_effort", effort))
            .put("stream", true)
    }

    fun stream(
        apiKey: String,
        model: String,
        messages: List<Msg>,
        effort: String,
        onThinking: (String) -> Unit,
        onContent: (String) -> Unit,
        onDone: (Throwable?) -> Unit
    ): Call {
        val body = buildBody(model, messages, effort).toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(cleanError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val errBody = try {
                            response.body?.string()
                        } catch (_: Exception) {
                            null
                        }
                        onDone(IOException(httpMessage(response.code, errBody)))
                        return
                    }
                    val source = response.body?.source() ?: run {
                        onDone(IOException("Empty response body"))
                        return
                    }
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val json = JSONObject(data)
                            val delta = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .optJSONObject("delta") ?: continue
                            val reasoning = delta.optString("reasoning_content", "")
                            if (reasoning.isNotEmpty()) onThinking(reasoning)
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) onContent(content)
                        } catch (_: Exception) {
                        }
                    }
                    onDone(null)
                } catch (e: Exception) {
                    onDone(cleanError(e))
                } finally {
                    response.close()
                }
            }
        })
        return call
    }

    private fun httpMessage(code: Int, body: String?): String {
        val hint = when (code) {
            401 -> "Invalid or missing API key. Check Settings."
            404 -> "Model not found. Try another model name in Settings."
            429 -> "Rate limit reached on NVIDIA free tier. Wait a bit."
            else -> "HTTP $code"
        }
        return "$hint${if (!body.isNullOrBlank()) "\n\n$body" else ""}"
    }

    private fun cleanError(e: Exception): Throwable {
        val msg = e.message ?: "Unknown error"
        return if (msg.contains("timeout", true)) {
            IOException("Connection timed out. Check your internet.")
        } else if (msg.contains("Unable to resolve", true) || msg.contains("network", true)) {
            IOException("No internet connection.")
        } else e
    }
}
