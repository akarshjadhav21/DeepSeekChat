package com.deepseek.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipInputStream

class BuilderActivity : Activity() {

    private val prefs by lazy { SecurePrefs.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var repo: String
    private var token: String = ""
    private var branch: String = "main"

    private var path: String = ""
    private val entries = mutableListOf<RepoEntry>()
    private lateinit var fileList: ListView
    private lateinit var filesAdapter: FilesAdapter
    private lateinit var statusView: TextView
    private lateinit var buildBtn: Button
    private lateinit var installBtn: View
    private lateinit var crumb: TextView

    private var polling = false
    private var lastRunCheckedId = -1L
    private var busyAi = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = prefs.getString("gh_token", "") ?: ""
        repo = prefs.getString("gh_repo", "")?.trim()?.trim('/') ?: ""

        if (token.isBlank() || repo.isBlank()) {
            Toast.makeText(this,
                "Set GitHub token + repo in ⚙ Settings first", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(topBar())

        crumb = TextView(this).apply {
            text = "/"
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 13f
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        root.addView(crumb)

        filesAdapter = FilesAdapter()
        fileList = ListView(this).apply {
            adapter = filesAdapter
            divider = null
            setBackgroundColor(Color.TRANSPARENT)
        }
        fileList.setOnItemClickListener { _, _, pos, _ ->
            val e = entries.getOrNull(pos) ?: return@setOnItemClickListener
            when {
                e.name == ".." -> {
                    path = path.substringBeforeLast("/", "")
                    refreshFiles()
                }
                e.type == "dir" -> {
                    path = e.path
                    refreshFiles()
                }
                else -> openEditor(e)
            }
        }
        root.addView(fileList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildSection())
        setContentView(root)

        Thread {
            try {
                val b = GitHubClient.defaultBranch(token, repo)
                runOnUiThread { branch = b; refreshFiles() }
            } catch (e: Exception) {
                runOnUiThread { toast("Repo check failed: ${e.message}") }
            }
        }.start()
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        bar.addView(TextView(this).apply {
            text = "🔨 $repo"
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(smallBtn("↻") { refreshFiles() })
        return bar
    }

    private fun buildSection(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        statusView = TextView(this).apply {
            text = "Ready."
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
        }
        box.addView(statusView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buildBtn = bigBtn("🔨 Build APK") { triggerBuild() }
        row.addView(buildBtn)
        installBtn = bigBtn("⬇ Install") { downloadAndInstall() }.apply { isEnabled = false; alpha = 0.4f }
        row.addView(installBtn)
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun smallBtn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.parseColor("#82B1FF"))
            background = null
            setOnClickListener { onClick() }
        }

    private fun bigBtn(label: String, onClick: () -> Unit): Button {
        val btn = Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0")); cornerRadius = 24f
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(8), dp(8), 0) }
        return btn
    }

    inner class FilesAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(p: Int): Any = entries[p]
        override fun getItemId(p: Int): Long = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val tv = cv as? TextView ?: TextView(this@BuilderActivity).apply {
                textSize = 14f
                setPadding(dp(20), dp(10), dp(12), dp(10))
            }
            val e = entries[p]
            tv.text = if (e.type == "dir") "📁 ${e.name}" else "📄 ${e.name}  (${e.size} B)"
            tv.setTextColor(if (e.name == "..") Color.parseColor("#82B1FF")
                            else if (e.type == "dir") Color.parseColor("#FFD54F")
                            else Color.WHITE)
            return tv
        }
    }

    private fun <T> bg(work: () -> T, ok: (T) -> Unit, err: (Exception) -> Unit) {
        Thread {
            try {
                val r = work()
                runOnUiThread { ok(r) }
            } catch (e: Exception) {
                runOnUiThread { err(e) }
            }
        }.start()
    }

    private fun refreshFiles() {
        crumb.text = "/" + path
        statusView.text = "Loading files…"
        bg({ GitHubClient.listContents(token, repo, branch, path) },
            { list ->
                entries.clear()
                if (path.isNotEmpty()) entries.add(RepoEntry("..", "", "up", 0, null))
                entries.addAll(list)
                filesAdapter.notifyDataSetChanged()
                statusView.text = "Ready."
            },
            { e ->
                statusView.text = "Load failed."
                toast(e.message ?: "error")
            })
    }

    private fun openEditor(entry: RepoEntry) {
        statusView.text = "Opening ${entry.name}…"
        bg({ GitHubClient.readFile(token, repo, branch, entry.path) },
            { (content, sha) -> showEditor(entry, content, sha) },
            { e -> toast("Read failed: ${e.message}") })
    }

    private fun showEditor(entry: RepoEntry, original: String, sha: String?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val title = TextView(this).apply {
            text = "📄 ${entry.name}"
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE); textSize = 15f
        }
        container.addView(title)

        val editor = EditText(this).apply {
            setText(original)
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            minLines = 8; maxLines = 14
            setHorizontallyScrolling(true)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161616")); cornerRadius = 16f
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        container.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))

        val prompt = EditText(this).apply {
            hint = "Tell DeepSeek what to change in this file…"
            setHintTextColor(Color.parseColor("#616161"))
            setTextColor(Color.WHITE); textSize = 13f
            maxLines = 2
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1C")); cornerRadius = 24f
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        container.addView(prompt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(6), 0, 0) })

        val dlg = AlertDialog.Builder(this).setView(container).create()

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var aiButton: Button? = null
        val aiBtn = bigBtn("🤖 AI Edit") {
            val instruction = prompt.text.toString().trim()
            if (instruction.isEmpty()) {
                toast("Type what to change first"); return@bigBtn
            }
            if (busyAi) { toast("AI is already editing…"); return@bigBtn }
            aiEditFile(aiButton, editor, instruction)
        }
        aiButton = aiBtn
        val saveBtn = bigBtn("💾 Push") {
            dlg.dismiss()
            commitFile(entry, editor.text.toString(), sha)
        }
        row.addView(aiBtn); row.addView(saveBtn)
        container.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(8), 0, dp(4)) })

        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96).toInt(),
            (resources.displayMetrics.heightPixels * 0.85).toInt())
        dlg.show()
    }

    private fun aiEditFile(btn: Button?, editor: EditText, instruction: String) {
        busyAi = true
        btn?.isEnabled = false
        btn?.text = "🤔 …"
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", NviClient.DEFAULT_MODEL)?.takeIf { it.isNotBlank() }
            ?: NviClient.DEFAULT_MODEL
        val sys = "You are a precise code editor. The user gives you a file and an instruction. " +
            "Return ONLY the complete modified file content — no explanations, no markdown fences."
        val user = "FILE:\n```\n${editor.text}\n```\n\nINSTRUCTION: $instruction"

        val acc = StringBuilder()
        NviClient.stream(apiKey, model,
            listOf(Msg("system", sys), Msg("user", user)), "high",
            onThinking = { _ -> },
            onContent = { chunk -> acc.append(chunk) },
            onDone = { err -> handler.post {
                busyAi = false
                btn?.isEnabled = true
                btn?.text = "🤖 AI Edit"
                var out = acc.toString().trim()
                if (out.startsWith("```")) {
                    out = out.removePrefix("```").substringAfter("\n", "").trimIndent()
                    out = out.removeSuffix("```").trimEnd()
                }
                if (err != null || out.isBlank()) {
                    toast(err?.message?.take(120) ?: "AI returned nothing")
                } else {
                    editor.setText(out)
                    toast("AI edit applied ✓ review & push")
                }
            }}
        )
    }

    private fun commitFile(entry: RepoEntry, newContent: String, sha: String?) {
        statusView.text = "Committing ${entry.name}…"
        bg({ GitHubClient.putFile(token, repo, branch, entry.path,
                newContent, sha, "Builder: update ${entry.name}") ; Pair(true, "") },
            { _ ->
                statusView.text = "Pushed ✓ build starting…"
                toast("Committed! Build will start automatically.")
                startPolling()
            },
            { e ->
                statusView.text = "Commit failed."
                toast(e.message ?: "commit error")
            })
    }

    private fun triggerBuild() {
        buildBtn.isEnabled = false
        statusView.text = "Dispatching workflow…"
        Thread {
            val dispatched = GitHubClient.dispatchBuild(token, repo)
            runOnUiThread {
                buildBtn.isEnabled = true
                if (dispatched) {
                    toast("Build triggered ✓")
                    startPolling()
                } else {
                    statusView.text = "Dispatch unavailable (build runs after each push)."
                    startPolling()
                }
            }
        }.start()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollOnce()
    }

    private fun pollOnce() {
        if (!polling) return
        bg({ GitHubClient.latestRun(token, repo) },
            { run ->
                when {
                    !polling -> Unit
                    run == null -> {
                        statusView.text = "No builds found."
                        polling = false
                    }
                    run.status == "completed" && run.conclusion == "success" -> {
                        statusView.text = "✅ Build success!"
                        polling = false
                        lastRunCheckedId = run.id
                        installBtn.isEnabled = true
                        installBtn.alpha = 1f
                    }
                    run.status == "completed" -> {
                        statusView.text = "❌ Build failed — fix code & push again."
                        polling = false
                    }
                    else -> {
                        statusView.text = "⏳ Building… (${run.status})"
                        scheduleNext()
                    }
                }
            },
            { _ -> if (polling) scheduleNext() })
    }

    private fun scheduleNext() {
        handler.postDelayed({ if (polling) pollOnce() }, 6000)
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
    }

    private fun downloadAndInstall() {
        installBtn.isEnabled = false
        statusView.text = "Downloading artifact…"
        bg({
            val run = GitHubClient.latestRun(token, repo)
                ?: throw Exception("No run found")
            val art = GitHubClient.artifactForRun(token, repo, run.id)
                ?: throw Exception("No artifact yet")
            val zipFile = File(cacheDir, "artifact.zip")
            GitHubClient.downloadArtifactZip(token, repo, art.first, zipFile)
            extractApk(zipFile)
        }, { apk ->
            installBtn.isEnabled = true
            installApk(apk)
        }, { e ->
            installBtn.isEnabled = true
            statusView.text = "Download failed."
            toast(e.message ?: "download error")
        })
    }

    @Suppress("RESULT_OF_CALL_IGNORED")
    private fun extractApk(zip: File): File {
        val outApk = File(filesDir, "builder_latest.apk")
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var found = false
            while (entry != null) {
                if (entry.name.endsWith(".apk")) {
                    outApk.outputStream().use { fos -> zis.copyTo(fos) }
                    found = true
                    break
                }
                entry = zis.nextEntry
            }
            if (!found) throw Exception("No APK inside artifact")
        }
        zip.delete()
        return outApk
    }

    private fun installApk(apk: File) {
        val uri = FileProvider.getUriForFile(this,
            "$packageName.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(i)
        } catch (_: Exception) {
            toast("Install blocked by Android — open the APK from Files app")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
