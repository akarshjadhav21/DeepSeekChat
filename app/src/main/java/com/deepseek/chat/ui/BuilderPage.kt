package com.deepseek.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.chat.C
import com.deepseek.chat.GitHubClient
import com.deepseek.chat.NviClient
import com.deepseek.chat.engine.AppStore
import java.io.File

@Composable
fun BuilderPage() {
    val ctx = LocalContext.current
    val prefs = AppStore.prefs()
    val token = prefs.getString("gh_token", "") ?: ""
    val repos = (prefs.getString("gh_repo", "") ?: "")
        .split(",").map { it.trim() }.filter { it.contains("/") }

    var repo by remember { mutableStateOf(repos.firstOrNull() ?: "") }
    var branch by remember { mutableStateOf("main") }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<GitHubClient.RepoEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<String, Pair<String, String?>>?>(null) } // path, (content, sha)
    var run by remember { mutableStateOf<GitHubClient.RunInfo?>(null) }
    var uploading by remember { mutableStateOf(false) }

    fun loadDir(p: String) {
        if (token.isBlank() || repo.isBlank()) return
        busy = true; status = "Loading $repo/$p…"
        Thread {
            try {
                val list = GitHubClient.listContents(token, repo, branch, p)
                AppStore.handler.post {
                    entries = list.sortedWith(compareBy({ it.type != "dir" }, { it.name }))
                    path = p; busy = false; status = ""
                }
            } catch (e: Exception) {
                AppStore.handler.post { busy = false; status = e.message ?: "load failed" }
            }
        }.start()
    }
    LaunchedEffect(repo) { if (repo.isNotBlank()) loadDir("") }

    // poll latest run while visible
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15000)
            if (token.isNotBlank() && repo.isNotBlank())
                try { run = GitHubClient.latestRun(token, repo) } catch (_: Exception) {}
        }
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || repo.isBlank()) return@rememberLauncherForActivityResult
        uploading = true; status = "Reading file…"
        Thread {
            try {
                val mime = ctx.contentResolver.getType(uri) ?: ""
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("cannot read file")
                if (bytes.size > 25 * 1024 * 1024) throw java.io.IOException("file > 25 MB — too big")
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "media.bin"
                val suggested = if (mime.startsWith("video")) "app/src/main/assets/videos/$name"
                                else "app/src/main/res/drawable/${name.substringBeforeLast('.')}.png"
                uploading = false
                // ask path via dialog state
                pendingUploadPath = suggested
                pendingUploadBytes = bytes
                pendingUploadRepo = repo
            } catch (e: Exception) {
                uploading = false; status = "✗ ${e.message}"
            }
        }.start()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Build 🔨", style = MaterialTheme.typography.titleLarge,
            color = C.textHi, modifier = Modifier.padding(bottom = 10.dp))

        if (repos.isEmpty()) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(C.card)) {
                Text("Add your repos in ⚙ Settings → “Builder repos”\n(comma-separated: user/RepoA, user/RepoB)",
                    color = C.textMid, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            return@Column
        }

        // repo chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in repos.take(4)) {
                FilterChip(selected = r == repo, onClick = { repo = r; path = ""; },
                    label = { Text(r.substringAfter('/'), fontSize = 12.sp) }, shape = CircleShape)
            }
        }
        Spacer(Modifier.height(8.dp))

        // toolbar
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (path.isBlank()) "/" else "/$path",
                color = C.textMid, fontSize = 13.sp, fontFamily = mono(),
                maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = { pickMedia.launch(arrayOf("image/*", "video/*")) }) {
                Icon(Icons.Filled.AddPhotoAlternate, "Upload media", tint = C.accent2) }
            IconButton(onClick = { loadDir(path) }) { Icon(Icons.Filled.Refresh, null, tint = C.textMid) }
        }

        when {
            busy -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.accent)
        }
        status.ifBlank { null }?.let {
            Text(it, color = C.textMid, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (path.isNotEmpty()) item {
                ListItem(headlineContent = { Text("..", color = C.accent, fontFamily = mono()) },
                    modifier = Modifier.clickable {
                        loadDir(path.substringBeforeLast('/', ""))
                    })
            }
            items(entries) { e ->
                ListItem(headlineContent = {
                    Text((if (e.type == "dir") "📁 " else "📄 ") + e.name,
                        color = C.textHi, fontSize = 14.sp, fontFamily = mono(), maxLines = 1)
                }, modifier = Modifier.clickable {
                    if (e.type == "dir") loadDir(e.path) else {
                        busy = true; status = "Opening ${e.name}…"
                        Thread {
                            try {
                                val (content, sha) = GitHubClient.readFile(token, repo, branch, e.path)
                                AppStore.handler.post {
                                    busy = false; status = ""
                                    editing = e.path to (content to sha)
                                }
                            } catch (ex: Exception) {
                                AppStore.handler.post { busy = false; status = ex.message ?: "read failed" }
                            }
                        }.start()
                    }
                })
            }
        }

        // build status stepper
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(C.card),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val (dotColor, label) = when (run?.status) {
                    "completed" -> C.green to "✅ last build passed"
                    "failure" -> C.red to "❌ last build failed"
                    "in_progress", "queued" -> C.amber to "⏳ building…"
                    else -> C.textLow to "no builds yet"
                }
                Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, color = C.textHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (run?.status == "completed") {
                    TextButton(onClick = {
                        Thread {
                            try {
                                status = "Downloading APK…"
                                val aid = GitHubClient.artifactForRun(token, repo, run!!.id)
                                    ?.first ?: return@Thread
                                val zipF = File(ctx.cacheDir, "build.zip")
                                GitHubClient.downloadArtifactZip(token, repo, aid, zipF)
                                val outApk = File(ctx.cacheDir, "built.apk")
                                java.util.zip.ZipInputStream(zipF.inputStream()).use { zis ->
                                    var e = zis.nextEntry
                                    while (e != null) {
                                        if (e.name.endsWith(".apk")) {
                                            outApk.outputStream().use { zis.copyTo(it) }
                                            break
                                        }
                                        e = zis.nextEntry
                                    }
                                }
                                status = ""
                                AppStore.intentEvent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        ctx, ctx.packageName + ".fileprovider", outApk)
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            } catch (e: Exception) { status = "✗ ${e.message}" }
                        }
                    }) { Text("📥 Install", fontSize = 12.sp) }
                }
            }
        }
    }

    // editor dialog
    editing?.let { (p, pair) ->
        val (initial, sha) = pair
        var text by remember(p) { mutableStateOf(initial) }
        var aiBusy by remember(p) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(p, fontSize = 14.sp, fontFamily = mono()) },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = C.textHi),
                        shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !aiBusy, onClick = {
                            aiBusy = true
                            Thread {
                                val out = try {
                                    NviClient.complete(
                                        prefs.getString("api_key", "") ?: "",
                                        prefs.getString("model", NviClient.DEFAULT_MODEL)
                                            ?: NviClient.DEFAULT_MODEL,
                                        prefs.getString("base_url", NviClient.DEFAULT_BASE)
                                            ?: NviClient.DEFAULT_BASE,
                                        "Edit this file per the instruction at the end. " +
                                            "Return ONLY the complete new file content, no markdown fences.\n\n" +
                                            "--- FILE: $p ---\n$text\n--- INSTRUCTION ---\n" +
                                            "(improve/fix this file)",
                                        maxTokens = 4096)
                                } catch (e: Exception) { "" }
                                AppStore.handler.post {
                                    aiBusy = false
                                    if (out.isNotBlank()) text = out
                                }
                            }.start()
                        }) { Text(if (aiBusy) "🤖 …" else "🤖 AI edit", fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = { Button(onClick = {
                val t = text; editing = null
                Thread {
                    try {
                        GitHubClient.putFile(token, repo, branch, p, t, sha,
                            "Builder: update $p")
                        AppStore.handler.post { status = "Pushed ✓ build starting…" }
                    } catch (e: Exception) {
                        AppStore.handler.post { status = "✗ ${e.message}" }
                    }
                }.start()
            }) { Text("💾 Push") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Close") } })
    }

    // media upload path confirm
    if (pendingUploadPath != null && pendingUploadBytes != null) {
        var p by remember { mutableStateOf(pendingUploadPath!!) }
        AlertDialog(onDismissRequest = {
            pendingUploadPath = null; pendingUploadBytes = null },
            title = { Text("Push media to repo") },
            text = {
                Column {
                    Text("Target path:", color = C.textMid, fontSize = 12.sp)
                    OutlinedTextField(value = p, onValueChange = { p = it },
                        singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = C.textHi))
                    Text("Images → res/drawable · Videos → assets/videos",
                        color = C.textLow, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { Button(onClick = {
                val pathP = p.trim(); val bytes = pendingUploadBytes!!
                pendingUploadPath = null; pendingUploadBytes = null
                status = "Uploading…"
                Thread {
                    try {
                        val sha = runCatching {
                            GitHubClient.readFile(token, repo, branch, pathP).second
                        }.getOrNull()
                        GitHubClient.putFileBinary(token, repo, branch, pathP, bytes, sha,
                            "Builder: add media $pathP")
                        AppStore.handler.post {
                            status = "Media pushed ✓ Ask AI to wire it into code!"
                        }
                    } catch (e: Exception) {
                        AppStore.handler.post { status = "✗ ${e.message}" }
                    }
                }.start()
            }) { Text("Push") } },
            dismissButton = { TextButton(onClick = {
                pendingUploadPath = null; pendingUploadBytes = null }) { Text("Cancel") } })
    }
}

private var pendingUploadPath by mutableStateOf<String?>(null)
private var pendingUploadBytes by mutableStateOf<ByteArray?>(null)
private var pendingUploadRepo by mutableStateOf<String?>(null)
