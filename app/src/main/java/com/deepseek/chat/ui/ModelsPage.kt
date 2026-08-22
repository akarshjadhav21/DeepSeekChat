package com.deepseek.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.chat.NviClient
import com.deepseek.chat.engine.AppStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ModelsRepo {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()

    fun visionCapable(id: String) = listOf("vision", "-vl", "neva", "vila", "kosmos")
        .any { id.lowercase().contains(it) }

    fun fetch(base: String, key: String): List<String> {
        val req = Request.Builder().url(base.trimEnd('/') + "/v1/models")
            .header("Authorization", "Bearer $key").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val arr = JSONObject(r.body?.string() ?: "").getJSONArray("data")
            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optString("id", "")
                if (id.isNotBlank()) ids.add(id)
            }
            return ids.sortedWith(compareBy({ !it.contains("deepseek") }, { it }))
        }
    }
}

private enum class Filter(val label: String) {
    All("All"), DeepSeek("DeepSeek"), Vision("👁 Vision"), Text("Text");

    fun matches(id: String): Boolean = when (this) {
        All -> true
        DeepSeek -> id.contains("deepseek", true)
        Vision -> ModelsRepo.visionCapable(id)
        Text -> !ModelsRepo.visionCapable(id)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelsPage() {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.All) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val prefs = AppStore.prefs()
    var chatModel by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var visionModel by remember { mutableStateOf(prefs.getString("vision_model", "") ?: "") }

    fun load() {
        loading = true; error = null
        Thread {
            try {
                val list = ModelsRepo.fetch(
                    prefs.getString("base_url", NviClient.DEFAULT_BASE) ?: NviClient.DEFAULT_BASE,
                    prefs.getString("api_key", "") ?: "")
                models = list
            } catch (e: Exception) { error = e.message } finally {
                loading = false
            }
        }.start()
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Models", style = MaterialTheme.typography.titleLarge,
            color = C.textHi, modifier = Modifier.padding(bottom = 10.dp))

        OutlinedTextField(value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search ${models?.size ?: ""}…", color = C.textLow) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = C.textMid) },
            shape = RoundedCornerShape(16.dp), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = C.textHi, unfocusedTextColor = C.textHi,
                focusedContainerColor = C.card, unfocusedContainerColor = C.card,
                focusedBorderColor = C.accent.copy(alpha = .5f),
                unfocusedBorderColor = Color.Transparent))

        Row(Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in Filter.entries) {
                FilterChip(selected = filter == f, onClick = { filter = f },
                    label = { Text(f.label, fontSize = 12.sp) },
                    shape = CircleShape)
            }
        }

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Loading models…", color = C.textMid, fontSize = 13.sp)
            }
            error != null -> Column {
                Text("⚠ $error", color = C.red, fontSize = 13.sp)
                TextButton(onClick = { load() }) { Text("Retry") }
            }
            else -> {
                val list = (models ?: emptyList())
                    .filter { it.contains(query, true) && filter.matches(it) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list) { id ->
                        val isChat = id == chatModel
                        val isVision = id == visionModel
                        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(
                            containerColor = if (isChat) Color(0xFF182334) else C.card),
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    chatModel = id
                                    prefs.edit().putString("model", id).apply()
                                },
                                onLongClick = {
                                    visionModel = id
                                    prefs.edit().putString("vision_model", id).apply()
                                    android.widget.Toast.makeText(AppStore.ctx(),
                                        "Vision model set: $id",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                })) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(id, color = C.textHi, fontSize = 14.sp,
                                    fontFamily = mono(), maxLines = 2)
                                Row(Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (ModelsRepo.visionCapable(id))
                                        Badge(text = "vision 👁", C.accent2)
                                    if (isChat) Badge(text = "chat ✓", C.green)
                                    if (isVision) Badge(text = "vision default", C.amber)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("Tap = use for chat · Long-press = set as vision default",
            color = C.textLow, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = .18f)) {
        Text(text, color = color, fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

// ---------- attach-time vision switcher ----------

@Composable
fun VisionModelDialog(onDismiss: () -> Unit) {
    val prefs = AppStore.prefs()
    var list by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Thread {
            try {
                list = ModelsRepo.fetch(
                    prefs.getString("base_url", NviClient.DEFAULT_BASE) ?: NviClient.DEFAULT_BASE,
                    prefs.getString("api_key", "") ?: "")
                    .filter { ModelsRepo.visionCapable(it) }
                if (list!!.isEmpty()) err = "No vision models available on this server"
            } catch (e: Exception) { err = e.message }
        }.start()
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("👁 Choose a vision model") },
        text = {
            when {
                list == null && err == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Loading…", color = C.textMid)
                }
                err != null -> Text("⚠ $err\n\nAdd one manually in the Models page.", color = C.red)
                else -> Column {
                    Text("Photos need a vision-capable model. Pick one:", color = C.textMid, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    for (m in list!!.take(12)) {
                        TextButton(onClick = {
                            prefs.edit().putString("model", m)
                                .putString("vision_model", m).apply()
                            onDismiss()
                        }) { Text(m, fontSize = 13.sp, color = C.accent) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
