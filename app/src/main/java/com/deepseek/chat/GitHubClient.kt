package com.deepseek.chat

import android.util.Base64
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

data class RepoEntry(
    val name: String,
    val path: String,
    val type: String,
    val size: Long,
    val sha: String?
)

data class RunInfo(
    val id: Long,
    val status: String,
    val conclusion: String?,
    val headSha: String?,
    val createdAt: String
)

object GitHubClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val API = "https://api.github.com"

    private fun req(token: String, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DeepSeekChat")

    private open class Sync<T>(val call: Call) {
        @Suppress("UNCHECKED_CAST")
        fun run(): T {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: Any? = null
            var error: Throwable? = null
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    error = e; latch.countDown()
                }
                override fun onResponse(c: Call, r: Response) {
                    try {
                        result = parse(r)
                    } catch (e: Exception) {
                        error = e
                    } finally {
                        r.close(); latch.countDown()
                    }
                }
            })
            latch.await(150, TimeUnit.SECONDS)
            error?.let { throw it }
            return result as T
        }

        open fun parse(r: Response): Any? = null
    }

    fun listContents(token: String, repo: String, branch: String, path: String): List<RepoEntry> {
        val url = "$API/repos/$repo/contents/$path?ref=$branch"
        val request = req(token, url).get().build()
        return object : Sync<List<RepoEntry>>(client.newCall(request)) {
            override fun parse(r: Response): Any {
                if (!r.isSuccessful) throw IOException(httpMsg(r.code, r.body?.string(), "list files"))
                val body = r.body!!.string()
                val entries = mutableListOf<RepoEntry>()
                if (body.trimStart().startsWith("[")) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        entries.add(RepoEntry(
                            o.getString("name"), o.getString("path"),
                            o.getString("type"), o.optLong("size", 0),
                            o.optString("sha", null)))
                    }
                } else {
                    val o = JSONObject(body)
                    entries.add(RepoEntry(
                        o.getString("name"), o.getString("path"),
                        o.getString("type"), o.optLong("size", 0),
                        o.optString("sha", null)))
                }
                entries.sortWith(compareBy({ if (it.type == "dir") 0 else 1 }, { it.name }))
                return entries
            }
        }.run()
    }

    fun readFile(token: String, repo: String, branch: String, path: String): Pair<String, String> {
        val url = "$API/repos/$repo/contents/$path?ref=$branch"
        val request = req(token, url).get().build()
        return object : Sync<Pair<String, String>>(client.newCall(request)) {
            override fun parse(r: Response): Any {
                if (!r.isSuccessful) throw IOException(httpMsg(r.code, r.body?.string(), "read file"))
                val o = JSONObject(r.body!!.string())
                val encoded = o.getString("content").replace("\n", "")
                val content = String(Base64.decode(encoded, Base64.DEFAULT))
                return Pair(content, o.getString("sha"))
            }
        }.run()
    }

    fun putFile(token: String, repo: String, branch: String, path: String,
                content: String, sha: String?, message: String) {
        val url = "$API/repos/$repo/contents/$path"
        val bodyObj = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            .put("branch", branch)
        if (!sha.isNullOrBlank()) bodyObj.put("sha", sha)
        val request = req(token, url)
            .put(bodyObj.toString().toRequestBody(JSON_TYPE))
            .build()
        return object : Sync<Unit>(client.newCall(request)) {
            override fun parse(r: Response) {
                if (!r.isSuccessful) throw IOException(httpMsg(r.code, r.body?.string(), "commit"))
            }
        }.run()
    }

    fun defaultBranch(token: String, repo: String): String {
        val request = req(token, "$API/repos/$repo").get().build()
        return object : Sync<String>(client.newCall(request)) {
            override fun parse(r: Response): Any {
                if (!r.isSuccessful) throw IOException(httpMsg(r.code, r.body?.string(), "repo info"))
                return JSONObject(r.body!!.string()).optString("default_branch", "main")
            }
        }.run()
    }

    fun dispatchBuild(token: String, repo: String): Boolean {
        val url = "$API/repos/$repo/actions/workflows/build.yml/dispatches"
        val request = req(token, url)
            .post(JSONObject().put("ref", "main").toString().toRequestBody(JSON_TYPE))
            .build()
        return try {
            client.newCall(request).execute().use { it.code == 204 }
        } catch (_: Exception) {
            false
        }
    }

    fun latestRun(token: String, repo: String): RunInfo? {
        val url = "$API/repos/$repo/actions/runs?per_page=1"
        val request = req(token, url).get().build()
        return object : Sync<RunInfo?>(client.newCall(request)) {
            override fun parse(r: Response): Any? {
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body!!.string()).optJSONArray("workflow_runs") ?: return null
                if (arr.length() == 0) return null
                val o = arr.getJSONObject(0)
                return RunInfo(o.getLong("id"), o.getString("status"),
                    o.optString("conclusion", null), o.optString("head_sha", null),
                    o.optString("created_at", ""))
            }
        }.run()
    }

    fun artifactForRun(token: String, repo: String, runId: Long): Pair<Long, String>? {
        val url = "$API/repos/$repo/actions/runs/$runId/artifacts"
        val request = req(token, url).get().build()
        return object : Sync<Pair<Long, String>?>(client.newCall(request)) {
            override fun parse(r: Response): Any? {
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body!!.string()).optJSONArray("artifacts") ?: return null
                if (arr.length() == 0) return null
                val o = arr.getJSONObject(0)
                return Pair(o.getLong("id"), o.getString("name"))
            }
        }.run()
    }

    fun downloadArtifactZip(token: String, repo: String, artifactId: Long, out: java.io.File) {
        val url = "$API/repos/$repo/actions/artifacts/$artifactId/zip"
        val request = req(token, url).get().build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException(httpMsg(r.code, null, "download"))
            out.outputStream().use { fos -> r.body!!.byteStream().copyTo(fos) }
        }
    }

    private fun httpMsg(code: Int, body: String?, what: String): String {
        val hint = when (code) {
            401 -> "Bad GitHub token. Check Settings."
            403 -> "Rate limited or forbidden."
            404 -> "Not found — check repo name in Settings ($what)."
            422 -> "GitHub rejected the request ($what)."
            else -> "HTTP $code ($what)"
        }
        val short = body?.let { b ->
            try { JSONObject(b).optString("message", "").take(200) } catch (_: Exception) { "" }
        } ?: ""
        return if (short.isBlank()) hint else "$hint\n$short"
    }
}
