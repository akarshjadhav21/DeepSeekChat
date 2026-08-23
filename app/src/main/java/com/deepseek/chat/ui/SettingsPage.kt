package com.deepseek.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.chat.ChatStore
import com.deepseek.chat.NviClient
import com.deepseek.chat.Reports
import com.deepseek.chat.engine.AppStore

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
        containerColor = C.card), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = C.accent)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit,
                  label: String, secret: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        label = { Text(label, color = C.textMid, fontSize = 12.sp) },
        singleLine = true, visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.textHi, unfocusedTextColor = C.textHi,
            focusedBorderColor = C.accent.copy(alpha = .5f),
            unfocusedBorderColor = Color.Transparent))
}

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val prefs = AppStore.prefs()
    var key by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var ghToken by remember { mutableStateOf(prefs.getString("gh_token", "") ?: "") }
    var ghRepos by remember { mutableStateOf(prefs.getString("gh_repo", "") ?: "") }
    var base by remember { mutableStateOf(prefs.getString("base_url", NviClient.DEFAULT_BASE) ?: NviClient.DEFAULT_BASE) }
    var effort by remember { mutableStateOf(prefs.getString("effort", "high") ?: "high") }
    var talkModel by remember { mutableStateOf(prefs.getString("talk_model", "") ?: "") }
    var visionModel by remember { mutableStateOf(prefs.getString("vision_model", "") ?: "") }
    var agentAuto by remember { mutableStateOf(AppStore.agentAuto) }
    var bubbleOn by remember { mutableStateOf(AppStore.bubbleOn) }
    var watchers by remember { mutableStateOf(com.deepseek.chat.Watchers.load(ctx)) }
    var watchOn by remember { mutableStateOf(com.deepseek.chat.Watchers.isOn(ctx)) }
    var watchMin by remember { mutableStateOf(prefs.getInt("watch_interval_min", 30)) }
    var newMetric by remember { mutableStateOf("battery") }
    var newOp by remember { mutableStateOf("<") }
    var newVal by remember { mutableStateOf("20") }
    var reportsOn by remember { mutableStateOf(prefs.getBoolean("report_enabled", false)) }
    var reportsHours by remember { mutableStateOf(prefs.getInt("report_hours", 12)) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // msg, isError
    var saving by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(ChatStore.serialize(AppStore.chats).toByteArray())
                } != null
            }.getOrDefault(false)
            AppStore.handler.post { status =
                (if (ok) "✓ Backup saved" else "✗ Backup failed") to !ok }
        }.start()
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->        if (uri == null) return@rememberLauncherForActivityResult
        Thread {
            val parsed = runCatching {
                ChatStore.deserialize(
                    ctx.contentResolver.openInputStream(uri)!!
                        .bufferedReader().readText())
            }.getOrNull()
            AppStore.handler.post {
                if (parsed.isNullOrEmpty()) status = "✗ Restore failed — bad file" to true
                else {
                    AppStore.chats = parsed
                    AppStore.activeId = parsed.firstOrNull()?.id
                    AppStore.persist()
                    status = "✓ Restored ${parsed.size} chats" to false
                }
            }
        }.start()
    }

    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    fun save() {
        saving = true; status = "Saving…" to false
        Thread {
            val result = try {
                val ok = prefs.edit()
                    .putString("api_key", key.trim())
                    .putString("gh_token", ghToken.trim())
                    .putString("gh_repo", ghRepos.trim())
                    .putString("base_url", base.trim().ifBlank { NviClient.DEFAULT_BASE })
                    .putString("effort",
                        if (effort in listOf("high","medium","low")) effort else "high")
                    .putString("talk_model", talkModel.trim())
                    .putString("vision_model", visionModel.trim())
                    .putBoolean("agent_auto", agentAuto)
                    .commit()
                if (ok && prefs.getString("api_key", "") == key.trim()) null
                else java.io.IOException("storage did not keep the value")
            } catch (e: Exception) { e }
            AppStore.handler.post {
                saving = false
                status = if (result == null) "✓ Saved" to false
                         else "✗ Save failed: ${result.message}" to true
            }
        }.start()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge,
            color = C.textHi, modifier = Modifier.padding(bottom = 12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("🔑 Keys") {
                Field(key, { key = it }, "NVIDIA API key (nvapi-…)", secret = true)
                Field(ghToken, { ghToken = it }, "GitHub token (repo+workflow)", secret = true)
                Field(ghRepos, { ghRepos = it }, "Builder repos (comma-separated)")
            }
            Section("🌐 Server") {
                Field(base, { base = it }, "Server URL / relay")
            }
            Section("🧠 Model behavior") {
                Text("Thinking effort", color = C.textMid, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (e in listOf("high", "medium", "low")) {
                        FilterChip(selected = effort == e, onClick = { effort = e },
                            label = { Text(e) }, shape = CircleShape)
                    }
                }
                Field(talkModel, { talkModel = it },
                    "🎙 Fast model — voice & bubble (blank = main)")
                Field(visionModel, { visionModel = it },
                    "👁 Vision model — screenshots & photos (blank = main)")
                Text("Three slots: 🧠 main chat (Models page) · 🎙 fast for voice · 👁 vision for images. Screenshots auto-use the vision slot.",
                    color = C.textLow, fontSize = 11.sp)
            }
            Section("🤖 Agent") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡ Auto-run commands", color = C.textHi,
                        modifier = Modifier.weight(1f))
                    Switch(checked = agentAuto, onCheckedChange = {
                        agentAuto = it; AppStore.agentAuto = it })
                }
                Text("Blocklist still enforced. Long-press 🤖 also toggles this.",
                    color = C.textLow, fontSize = 11.sp)
            }
            Section("🖐 Hands") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🫧 Floating bubble", color = C.textHi,
                        modifier = Modifier.weight(1f))
                    Switch(checked = bubbleOn, onCheckedChange = { on ->
                        if (on) {
                            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                                AppStore.intentEvent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + ctx.packageName))
                                status = "Grant 'Display over other apps', then flip this on" to false
                            } else if (com.deepseek.chat.Bubble.start(ctx)) {
                                bubbleOn = true
                                status = "🫧 Bubble floating — tap it over any app" to false
                            } else status = "Couldn't start bubble" to true
                        } else {
                            com.deepseek.chat.Bubble.stop(ctx)
                            bubbleOn = false
                        }
                    })
                }
                Text("Chat with the AI over any app — it can read & tap the screen underneath while you ask.",
                    color = C.textLow, fontSize = 11.sp)
                OutlinedButton(onClick = {
                    AppStore.intentEvent = android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (com.deepseek.chat.AgentAccessibilityService.connected)
                        "♿ Screen control: ON ✓ (tap to manage)"
                    else "♿ Enable screen control")
                }
                Text("One-time: Android Settings → Accessibility → DeepSeek Chat → ON. Lets ui-tap / ui-read verbs work inside other apps.",
                    color = C.textLow, fontSize = 11.sp)
            }
            Section("👀 Watchers") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Check in background", color = C.textHi,
                        modifier = Modifier.weight(1f))
                    Switch(checked = watchOn, onCheckedChange = { on ->
                        com.deepseek.chat.Watchers.setEnabled(ctx, on)
                        watchOn = on
                        status = (if (on) "👀 Watchers run every ${watchMin}min"
                        else "Watchers off") to false
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(15, 30, 60)) {
                        FilterChip(selected = watchMin == m, onClick = {
                            watchMin = m
                            if (watchOn) com.deepseek.chat.Watchers.schedule(ctx, m)
                            else prefs.edit().putInt("watch_interval_min", m).commit()
                        }, label = { Text("${m}m") }, shape = CircleShape)
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (watchers.isEmpty())
                    Text("No watchers yet — add one below (e.g. 🔋 Battery < 20%).",
                        color = C.textLow, fontSize = 12.sp)
                for (w in watchers) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(com.deepseek.chat.Watchers.ruleText(w), color = C.textHi,
                            fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = w.enabled, onCheckedChange = { en ->
                            watchers = watchers.map {
                                if (it.id == w.id) it.copy(enabled = en) else it
                            }
                            com.deepseek.chat.Watchers.save(ctx, watchers)
                        })
                        Text("✕", color = C.red, fontSize = 15.sp,
                            modifier = Modifier.padding(start = 10.dp).clickable {
                                watchers = watchers.filterNot { it.id == w.id }
                                com.deepseek.chat.Watchers.save(ctx, watchers)
                            })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("New watcher", color = C.textMid, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    for ((k, lbl) in listOf("battery" to "🔋", "storage" to "💾", "ram" to "🧠")) {
                        FilterChip(selected = newMetric == k, onClick = { newMetric = k },
                            label = { Text(lbl) }, shape = CircleShape)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    for (o in listOf("<", ">")) {
                        FilterChip(selected = newOp == o, onClick = { newOp = o },
                            label = { Text(o) }, shape = CircleShape)
                    }
                    OutlinedTextField(value = newVal, onValueChange = { v ->
                        newVal = v.filter { it.isDigit() }.take(3)
                    }, label = { Text("%") }, singleLine = true,
                        modifier = Modifier.width(90.dp),
                        shape = RoundedCornerShape(12.dp))
                    Button(onClick = {
                        val v = newVal.toIntOrNull() ?: return@Button
                        val r = com.deepseek.chat.WatchRule(
                            java.util.UUID.randomUUID().toString(), newMetric, newOp, v)
                        watchers = watchers + r
                        com.deepseek.chat.Watchers.save(ctx, watchers)
                        newVal = "20"
                        status = "✓ Watcher added${if (!watchOn) " — turn on 'Check in background'" else ""}" to false
                    }, shape = CircleShape) { Text("+ Add") }
                }
                Text("Alerts fire once per trip → notification + entry in the 👀 Watchers chat. AI writes a short suggestion.",
                    color = C.textLow, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Section("⏰ Scheduled reports") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device report", color = C.textHi, modifier = Modifier.weight(1f))
                    Switch(checked = reportsOn, onCheckedChange = { on ->
                        reportsOn = on
                        prefs.edit().putBoolean("report_enabled", on).commit()
                        if (on) {
                            if (android.os.Build.VERSION.SDK_INT >= 33)
                                notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            Reports.schedule(ctx, reportsHours)
                            status = "⏰ Reports every ${reportsHours}h" to false
                        } else {
                            Reports.cancel(ctx)
                            status = "Reports off" to false
                        }
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp)) {
                    for (h in listOf(6, 12, 24)) {
                        FilterChip(selected = reportsHours == h, onClick = {
                            reportsHours = h
                            prefs.edit().putInt("report_hours", h).commit()
                            if (reportsOn) Reports.schedule(ctx, h)
                        }, label = { Text("${h}h") }, shape = CircleShape)
                    }
                }
                OutlinedButton(onClick = {
                    status = "Building report… (model can take minutes on free tier)" to false
                    Thread {
                        try {
                            Reports.gatherAndDeliver(ctx)
                            AppStore.handler.post { status =
                                "✓ Report saved — see 📊 Scheduled reports chat" to false }
                        } catch (e: Exception) {
                            AppStore.handler.post { status =
                                "✗ Report failed: ${e.message}" to true }
                        }
                    }.start()
                }, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Text("▶ Run now")
                }
                Text("Runs in background (WorkManager): collects battery/storage/RAM stats, AI-summarizes, drops result into the 📊 chat + notification.",
                    color = C.textLow, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Section("💾 Backup & restore") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { exportLauncher.launch("deepseekchat-backup.json") },
                        shape = RoundedCornerShape(14.dp)) { Text("⬆ Export all chats") }
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }, shape = RoundedCornerShape(14.dp)) { Text("⬇ Import") }
                }
                Text("Export saves every chat as JSON. Import replaces current chats.",
                    color = C.textLow, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }

            Section("ℹ️ About") {
                Text("DeepSeek Chat v3.10 · Model slots", color = C.textMid, fontSize = 13.sp)
                Text("Screen control · screenshot Q&A · floating bubble · device actions · plan mode · reports · voice · vision. Free keys: build.nvidia.com",
                    color = C.textLow, fontSize = 12.sp)
            }

            status?.let { (msg, isErr) ->
                Text(msg, color = if (isErr) C.red else C.green, fontSize = 13.sp)
            }
            Button(onClick = { save() }, enabled = !saving,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = Color.White)
                else Text("💾  Save settings", fontSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
