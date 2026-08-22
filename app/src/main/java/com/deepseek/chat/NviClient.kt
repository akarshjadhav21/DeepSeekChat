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

object NviClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(360, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    const val DEFAULT_BASE = "https://integrate.api.nvidia.com"
    const val DEFAULT_MODEL = "deepseek-ai/deepseek-v4-flash-0731"
    const val STOP = "__user_stopped__"

    fun buildBody(model: String, messages: List<Msg>, effort: String = "high"): JSONObject {
        val arr = JSONArray()
        for (m in messages) {
            val mo = JSONObject().put("role", m.role)
            if (m.images.isEmpty()) {
                mo.put("content", m.content)
            } else {
                val parts = JSONArray()
                if (m.content.isNotBlank())
                    parts.put(JSONObject().put("type", "text").put("text", m.content))
                for (img in m.images) {
                    val b64 = android.util.Base64.encodeToString(
                        java.io.File(img).readBytes(), android.util.Base64.NO_WRAP)
                    parts.put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                }
                mo.put("content", parts)
            }
            arr.put(mo)
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
        onDone: (Throwable?) -> Unit,
        onConnected: (() -> Unit)? = null,
        baseUrl: String = DEFAULT_BASE
    ): Call {
        val body = buildBody(model, messages, effort).toString().toRequestBody(JSON_TYPE)
        val base = baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(if (call.isCanceled()) IOException(STOP) else cleanError(e))
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
                    onConnected?.invoke()
                    var lines = 0
                    var events = 0
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        lines++
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val json = JSONObject(data)
                            val errObj = json.optJSONObject("error")
                            if (errObj != null) {
                                events++
                                throw IOException(
                                    "Server error: ${errObj.optString("message", "unknown")}")
                            }
                            val delta = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .optJSONObject("delta") ?: continue
                            val reasoning = delta.optString("reasoning_content", "")
                            if (reasoning.isNotEmpty()) {
                                events++
                                onThinking(reasoning)
                            }
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                events++
                                onContent(content)
                            }
                        } catch (e: IOException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    if (events == 0) {
                        onDone(IOException(
                            "Model returned no data ($lines lines). " +
                            "It may be overloaded — try again, or switch model in Settings."))
                    } else {
                        onDone(null)
                    }
                } catch (e: Exception) {
                    onDone(if (call.isCanceled()) IOException(STOP) else cleanError(e))
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
            403 -> "Key rejected — regenerate a free one at build.nvidia.com."
            404 -> "Model not found. Try another model name in Settings."
            429 -> "Rate limit reached on NVIDIA free tier. Wait a bit."
            500, 502, 503 -> "NVIDIA server busy ($code). Try again shortly."
            504 -> "NVIDIA's queue gave up after ~5 min. Free tier is overloaded — just retry, streaming usually gets through."
            else -> "HTTP $code"
        }
        return "$hint${if (!body.isNullOrBlank()) "\n\n$body" else ""}"
    }

    private fun cleanError(e: Exception): Throwable {
        val msg = e.message ?: "Unknown error"
        return if (msg.contains("timeout", true)) {
            IOException("Gave up waiting — free-tier queue can take 4+ min today. Tap retry.")
        } else if (msg.contains("Unable to resolve", true) || msg.contains("network", true)) {
            IOException("No internet connection.")
        } else e
    }

    /** Small synchronous non-streaming completion (used for chat titles). */
    fun complete(key: String, model: String, base: String, prompt: String,
                 maxTokens: Int = 24): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", maxTokens)
            .put("temperature", 0.3)
            .toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url(base.trimEnd('/') + "/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return ""
            val j = org.json.JSONObject(r.body?.string() ?: "")
            return j.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "").trim()
        }
    }
}
