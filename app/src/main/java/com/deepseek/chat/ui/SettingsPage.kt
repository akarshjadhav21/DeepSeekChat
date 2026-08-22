package com.deepseek.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    var agentAuto by remember { mutableStateOf(AppStore.agentAuto) }
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
                Text("DeepSeek Chat v3.2.0 · Plan & Reports", color = C.textMid, fontSize = 13.sp)
                Text("Plan-mode agent · scheduled reports · voice in 11 languages · vision · builder. Free keys: build.nvidia.com",
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
