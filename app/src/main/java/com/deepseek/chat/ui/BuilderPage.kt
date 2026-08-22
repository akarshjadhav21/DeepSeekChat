package com.deepseek.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.deepseek.chat.BuilderTemplates
import com.deepseek.chat.GitHubClient
import com.deepseek.chat.NviClient
import com.deepseek.chat.RepoEntry
import com.deepseek.chat.RunInfo
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
    var entries by remember { mutableStateOf<List<RepoEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<String, Pair<String, String?>>?>(null) } // path, (content, sha)
    var repoRuns by remember { mutableStateOf<Map<String, RunInfo?>>(emptyMap()) }
    var uploading by remember { mutableStateOf(false) }
    var showTpl by remember { mutableStateOf(false) }
    var tplPick by remember { mutableStateOf<BuilderTemplates.Tpl?>(null) }
    // one-tap versioning draft: versionCode, versionName, tag
    var verDraft by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    var verSha by remember { mutableStateOf<String?>(null) }
    var verContent by remember { mutableStateOf<String?>(null) }

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

    // poll latest runs for ALL configured repos (dashboard) while visible
    LaunchedEffect(Unit) {
        while (true) {
            if (token.isNotBlank())
                for (r in repos) {
                    val ri = try { GitHubClient.latestRun(token, r) } catch (_: Exception) { null }
                    AppStore.handler.post { repoRuns = repoRuns + (r to ri) }
                }
            kotlinx.coroutines.delay(20000)
        }
    }

    // one-tap versioning: read gradle, bump patch, offer tag
    fun openBump() {
        if (busy || repo.isBlank()) return
        busy = true; status = "Reading version…"
        Thread {
            try {
                val (gradle, sha) = GitHubClient.readFile(token, repo, branch, "app/build.gradle.kts")
                val codeM = Regex("versionCode\\s*=\\s*(\\d+)").find(gradle)
                    ?: throw java.io.IOException("versionCode not found in app/build.gradle.kts")
                val nameM = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(gradle)
                    ?: throw java.io.IOException("versionName not found in app/build.gradle.kts")
                val newCode = codeM.groupValues[1].toInt() + 1
                val parts = nameM.groupValues[1].split('.')
                val newName = if (parts.size >= 3 && parts.last().toIntOrNull() != null)
                    parts.dropLast(1).joinToString(".") + "." + (parts.last().toInt() + 1)
                else nameM.groupValues[1] + ".1"
                AppStore.handler.post {
                    busy = false; status = ""
                    verSha = sha; verContent = gradle
                    verDraft = Triple(newCode, newName, "v$newName")
                }
            } catch (e: Exception) {
                AppStore.handler.post { busy = false; status = "✗ ${e.message}" }
            }
        }.start()
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

        // multi-repo dashboard — last-build status per configured repo
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(repos) { r ->
                val ri = repoRuns[r]
                val dot = when (ri?.status) {
                    "completed" -> C.green
                    "failure" -> C.red
                    "in_progress", "queued" -> C.amber
                    else -> C.textLow
                }
                Card(shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (r == repo) C.card else C.surface),
                    border = if (r == repo) androidx.compose.foundation.BorderStroke(1.dp, C.accent.copy(alpha = .6f)) else null,
                    modifier = Modifier.clickable { repo = r; path = "" }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(r.substringAfter('/'), fontSize = 12.sp,
                            color = C.textHi, maxLines = 1)
                        ri?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (it.status) {
                                    "completed" -> "✅"
                                    "failure" -> "❌"
                                    "in_progress", "queued" -> "⏳"
                                    else -> ""
                                }, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // toolbar
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (path.isBlank()) "/" else "/$path",
                color = C.textMid, fontSize = 13.sp, fontFamily = mono(),
                maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = { showTpl = true }) {
                Icon(Icons.Filled.LibraryAdd, "New from template", tint = C.green) }
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
                val run = repoRuns[repo]
                val (dotColor, label) = when (run?.status) {
                    "completed" -> C.green to "✅ last build passed"
                    "failure" -> C.red to "❌ last build failed"
                    "in_progress", "queued" -> C.amber to "⏳ building…"
                    else -> C.textLow to "no builds yet"
                }
                Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, color = C.textHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { openBump() }, enabled = !busy && repo.isNotBlank()) {
                    Icon(Icons.Filled.Sell, "New release (bump version + tag)", tint = C.accent2) }
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

    // templates gallery
    if (showTpl) {
        AlertDialog(onDismissRequest = { showTpl = false },
            title = { Text("🖼 New project from template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tpl in BuilderTemplates.all) {
                        Card(shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(C.surface),
                            modifier = Modifier.fillMaxWidth().clickable {
                                showTpl = false; tplPick = tpl }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${tpl.emoji} ${tpl.name}", color = C.textHi,
                                    fontSize = 15.sp, fontFamily = mono())
                                Spacer(Modifier.height(4.dp))
                                Text(tpl.desc, color = C.textMid, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTpl = false }) { Text("Close") } })
    }

    // template → repo name → create & push
    tplPick?.let { tpl ->
        var rn by remember(tpl) { mutableStateOf(tpl.suggestedRepo) }
        AlertDialog(onDismissRequest = { tplPick = null },
            title = { Text("Create ${tpl.emoji} ${tpl.name}") },
            text = {
                Column {
                    OutlinedTextField(value = rn, onValueChange = { rn = it },
                        singleLine = true, label = { Text("New repo name", color = C.textMid) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = C.textHi))
                    Spacer(Modifier.height(6.dp))
                    Text("Repo is created under your GitHub account (${repos.firstOrNull()?.substringBefore('/') ?: "you"}), template files pushed to main, CI starts automatically.",
                        color = C.textLow, fontSize = 11.sp)
                }
            },
            confirmButton = { Button(onClick = {
                val rname = rn.trim().substringAfterLast('/').ifBlank { return@Button }
                tplPick = null
                busy = true
                Thread {
                    try {
                        AppStore.handler.post { status = "Creating repo $rname…" }
                        val full = GitHubClient.createRepo(token, rname)
                        val files = tpl.files
                        for ((i, f) in files.withIndex()) {
                            AppStore.handler.post { status =
                                "Pushing ${i + 1}/${files.size}: ${f.first}" }
                            GitHubClient.putFile(token, full, "main", f.first, f.second, null,
                                "Template ${tpl.name}: add ${f.first}")
                        }
                        val cur = (prefs.getString("gh_repo", "") ?: "")
                            .split(",").map { it.trim() }.filter { it.isNotBlank() }
                        if (!cur.contains(full))
                            prefs.edit().putString("gh_repo", (cur + full).joinToString(",")).commit()
                        AppStore.handler.post {
                            busy = false
                            repo = full; path = ""; loadDir("")
                            status = "✓ $full created — CI building…"
                        }
                    } catch (e: Exception) {
                        AppStore.handler.post { busy = false; status = "✗ ${e.message}" }
                    }
                }.start()
            }) { Text("Create & push") } },
            dismissButton = { TextButton(onClick = { tplPick = null }) { Text("Cancel") } })
    }

    // one-tap versioning: commit bumped gradle + push tag
    verDraft?.let { draft ->
        val (code0, name0, tag0) = draft
        var c by remember(draft) { mutableStateOf(code0.toString()) }
        var n by remember(draft) { mutableStateOf(name0) }
        var tg by remember(draft) { mutableStateOf(tag0) }
        AlertDialog(onDismissRequest = { verDraft = null },
            title = { Text("🏷 Release $repo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = c, onValueChange = { c = it }, singleLine = true,
                        label = { Text("versionCode", color = C.textMid) })
                    OutlinedTextField(value = n, onValueChange = { n = it }, singleLine = true,
                        label = { Text("versionName", color = C.textMid) })
                    OutlinedTextField(value = tg, onValueChange = { tg = it }, singleLine = true,
                        label = { Text("Tag (v*) — triggers signed release", color = C.textMid) })
                    Text("Commits app/build.gradle.kts then tags HEAD. The release workflow must exist in this repo.",
                        color = C.textLow, fontSize = 11.sp)
                }
            },
            confirmButton = { Button(onClick = {
                val code = c.trim().toIntOrNull() ?: code0
                val vname = n.trim(); val tag = tg.trim()
                val sha0 = verSha; val old = verContent
                verDraft = null
                if (sha0 == null || old == null || tag.isBlank()) return@Button
                busy = true; status = "Committing version bump…"
                Thread {
                    try {
                        var g = Regex("versionCode\\s*=\\s*\\d+")
                            .replace(old) { "versionCode = $code" }
                        g = Regex("versionName\\s*=\\s*\"[^\"]+\"")
                            .replace(g) { "versionName = \"$vname\"" }
                        GitHubClient.putFile(token, repo, branch, "app/build.gradle.kts", g, sha0,
                            "Release $vname")
                        Thread.sleep(1500)
                        val head = GitHubClient.headSha(token, repo, branch)
                        GitHubClient.createRef(token, repo, "refs/tags/$tag", head)
                        AppStore.handler.post {
                            busy = false
                            status = "🏷 $tag pushed — release building…"
                        }
                    } catch (e: Exception) {
                        AppStore.handler.post { busy = false; status = "✗ ${e.message}" }
                    }
                }.start()
            }) { Text("Commit + tag") } },
            dismissButton = { TextButton(onClick = { verDraft = null }) { Text("Cancel") } })
    }
}

private var pendingUploadPath by mutableStateOf<String?>(null)
private var pendingUploadBytes by mutableStateOf<ByteArray?>(null)
private var pendingUploadRepo by mutableStateOf<String?>(null)
