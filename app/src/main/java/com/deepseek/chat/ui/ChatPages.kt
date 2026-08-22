package com.deepseek.chat.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import coil.compose.AsyncImage
import com.deepseek.chat.*
import com.deepseek.chat.engine.AgentConfirm
import com.deepseek.chat.engine.AppStore
import com.deepseek.chat.engine.Media

// ================= Chats list =================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsListPage(onOpen: (String) -> Unit) {
    var deleteTarget by remember { mutableStateOf<Chat?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, color = C.textHi,
                modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(AppStore.chats, key = { it.id }) { chat ->
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = if (chat.id == AppStore.activeId) C.card else C.surface),
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { AppStore.activeId = chat.id; onOpen(chat.id) },
                            onLongClick = { deleteTarget = chat })) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("💬 ", fontSize = 15.sp)
                                Text(chat.title.ifBlank { "New chat" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = C.textHi, maxLines = 1)
                            }
                            val last = chat.msgs.lastOrNull()
                            Text(last?.content?.take(90)?.replace('\n', ' ') ?: "No messages yet",
                                color = C.textMid, fontSize = 13.sp, maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { AppStore.newChat() },
            containerColor = C.accent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Icon(Icons.Filled.Add, "New chat", tint = Color.White)
        }
    }
    deleteTarget?.let { c ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${c.title}\" will be removed permanently.") },
            confirmButton = { TextButton(onClick = {
                AppStore.deleteChat(c.id); deleteTarget = null }) { Text("Delete", color = C.red) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } })
    }
}

// ================= Conversation =================

private val quickPrompts = listOf("Summarize", "Translate to English",
    "Fix grammar", "Explain this code", "Brainstorm ideas")

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationPage(chatId: String, onBack: () -> Unit, onNeedSettings: () -> Unit) {
    val ctx = LocalContext.current
    val chat = AppStore.chats.firstOrNull { it.id == chatId }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showVisionDialog by remember { mutableStateOf(false) }

    fun shareMarkdown() {
        val md = StringBuilder("# ${chat?.title}\n")
        for (m in chat?.msgs ?: emptyList()) {
            md.append("\n## ").append(if (m.role == "user") "🧑 You" else "🤖 DeepSeek")
                .append("\n").append(m.content).append('\n')
        }
        ctx.startActivity(Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, chat?.title)
            putExtra(Intent.EXTRA_TEXT, md.toString())
        })
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread {
            try {
                val mime = ctx.contentResolver.getType(uri)
                val copied = Media.copyIn(ctx, uri, mime)
                val files = if (mime?.startsWith("video") == true) {
                    Media.videoFrames(ctx, copied, 4)
                } else { Media.downscaleImage(copied); listOf(copied) }
                AppStore.pendingImages = files
                val model = AppStore.prefs().getString("model", NviClient.DEFAULT_MODEL) ?: ""
                if (!model.contains("vision") && !model.contains("-vl")) showVisionDialog = true
            } catch (e: Exception) {
                AppStore.errorText = e.message ?: "attach failed"
            }
        }.start()
    }

    Scaffold(containerColor = C.bg, topBar = {
        Row(Modifier.fillMaxWidth().background(C.surface).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.textHi) }
            Text(chat?.title ?: "", style = MaterialTheme.typography.titleMedium,
                color = C.textHi, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = { shareMarkdown() }) {
                Icon(Icons.Filled.IosShare, "Export", tint = C.textMid) }
            // agent toggle: tap=on/off, long-press=auto-run
            Box(Modifier.clip(CircleShape).background(if (AppStore.agentOn) Color(0xFF173B25) else Color.Transparent)
                .combinedClickable(onClick = { AppStore.agentOn = !AppStore.agentOn },
                    onLongClick = {
                        AppStore.agentAuto = !AppStore.agentAuto
                        android.widget.Toast.makeText(ctx,
                            if (AppStore.agentAuto) "⚡ Auto-run ON" else "Auto-run OFF — confirm each command",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }).padding(8.dp)) {
                Text("🤖", fontSize = 19.sp)
                if (AppStore.agentOn) Box(Modifier.size(7.dp).clip(CircleShape)
                    .background(C.green).align(Alignment.TopEnd))
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val msgs = chat?.msgs ?: emptyList()
            val itemCount = msgs.size +
                (if (AppStore.thinkingText != null) 1 else 0) +
                (if (AppStore.liveContent != null) 1 else 0) +
                (if (AppStore.toolText != null) 1 else 0) +
                (if (AppStore.statusText.isNotEmpty()) 1 else 0) +
                (if (AppStore.errorText != null) 1 else 0)

            LazyColumn(state = listState, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs) { m ->
                    when {
                        m.role == "user" -> UserBubble(m.content, m.images)
                        else -> AiBubble(m.content)
                    }
                }
                AppStore.thinkingText?.let { t -> item { ThinkingBubble(t) } }
                AppStore.liveContent?.let { t -> item { AiBubble(t) } }
                AppStore.toolText?.let { t -> item { ToolBubble(t) } }
                if (AppStore.statusText.isNotEmpty()) item { StatusLine(AppStore.statusText) }
                AppStore.errorText?.let { e -> item { ErrorBubble(e) } }
            }

            LaunchedEffect(itemCount) {
                if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
            }

            if (!AppStore.busy && input.isEmpty())
                QuickPromptsRow { input = it }

            PendingImagesRow()

            InputBar(
                input = input, onInput = { input = it },
                busy = AppStore.busy,
                agentOn = AppStore.agentOn,
                canAttach = !AppStore.busy,
                onAttach = {
                    pickMedia.launch(arrayOf("image/*", "video/*"))
                },
                onSend = {
                    AppStore.send(input) { onNeedSettings() }
                    input = ""
                },
                onStop = { AppStore.stopStreaming() })
        }
    }

    // agent command confirmation
    AppStore.pendingConfirm?.let { c ->
        AlertDialog(onDismissRequest = {}, title = { Text("🤖 Run this command?") },
            text = { Text(c.cmd, fontFamily = mono(), fontSize = 13.sp) },
            confirmButton = { Button(onClick = { AppStore.approvePending() }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { AppStore.denyPending() }) { Text("Deny") } })
    }

    if (showVisionDialog) VisionModelDialog(onDismiss = { showVisionDialog = false })
}

// ---------- pieces ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickPromptsRow(onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in quickPrompts) {
            AssistChip(onClick = { onPick(p) }, label = { Text(p, fontSize = 12.sp) },
                shape = CircleShape, colors = AssistChipDefaults.assistChipColors(
                    labelColor = C.accent))
        }
    }
}

@Composable
fun PendingImagesRow() {
    if (AppStore.pendingImages.isEmpty()) return
    Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in AppStore.pendingImages) {
            val bmp = remember(f.absolutePath) {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            }
            Box {
                if (bmp != null) Image(bmp.asImageBitmap(), null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                Surface(shape = CircleShape, color = C.red, modifier = Modifier
                    .size(18.dp).align(Alignment.TopEnd).clickable {
                        AppStore.pendingImages = AppStore.pendingImages.filterNot { it == f }
                    }) { Text("×", color = Color.White, fontSize = 11.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
fun InputBar(input: String, onInput: (String) -> Unit, busy: Boolean, agentOn: Boolean,
             canAttach: Boolean, onAttach: () -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(value = input, onValueChange = onInput, modifier = Modifier.weight(1f),
            placeholder = { Text(if (agentOn) "Ask the agent…" else "Message DeepSeek…",
                color = C.textLow) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = C.textHi, unfocusedTextColor = C.textHi,
                focusedContainerColor = C.card, unfocusedContainerColor = C.card,
                focusedBorderColor = C.accent.copy(alpha = .5f),
                unfocusedBorderColor = Color.Transparent),
            maxLines = 5)
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onAttach, enabled = canAttach,
            shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = C.card, contentColor = C.accent)) {
            Icon(Icons.Filled.AttachFile, "Attach")
        }
        Spacer(Modifier.width(6.dp))
        Button(onClick = if (busy) onStop else onSend,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (busy) C.red else C.accent,
                contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
            Text(if (busy) "■" else "➤", fontSize = 17.sp)
        }
    }
}

@Composable
fun UserBubble(text: String, images: List<String> = emptyList()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.End) {
        if (images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in images.take(6)) AsyncImage(model = java.io.File(p),
                contentDescription = null,
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)))
        }
        Box(background(C.userBubble(), RoundedCornerShape(20.dp)).padding(12.dp)) {
            Text(text, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
fun AiBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(end = 36.dp)) {
        Avatar("🤖")
        Box(Modifier.offset(y = (-2).dp).weight(1f)) {
            Box(background(C.bubbleAi, RoundedCornerShape(20.dp)).padding(12.dp)) {
                AndroidTextView(Markdown.render(text))
            }
        }
    }
}

@Composable
fun ThinkingBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 36.dp)) {
        Box(background(Color(0xFF141922), RoundedCornerShape(16.dp)).padding(10.dp)) {
            Column {
                Text("💭 thinking", color = C.textLow, fontSize = 11.sp)
                Text(text.takeLast(600), color = C.textMid, fontSize = 12.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        }
    }
}

@Composable
fun ToolBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 36.dp)) {
        Box(background(C.toolBg, RoundedCornerShape(14.dp)).padding(10.dp)) {
            Text(text, color = Color(0xFF7CE38B), fontSize = 12.sp, fontFamily = mono())
        }
    }
}

@Composable
fun ErrorBubble(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 44.dp)
        .background(Color(0xFF3A1518), RoundedCornerShape(14.dp)).padding(10.dp)) {
        Text("⚠ $text", color = Color(0xFFFF8A80), fontSize = 13.sp)
    }
}

@Composable
fun StatusLine(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        TypingDots()
        Text("  $text", color = C.textMid, fontSize = 12.sp)
    }
}

@Composable
fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(
        tween(900, easing = LinearEasing)), label = "phase")
    Row {
        for (i in 0 until 3) {
            val a = ((phase - i + 3f) % 3f)
            val alpha = if (a < 1f) 0.35f + 0.65f * a else 0.35f
            Box(Modifier.size(7.dp).clip(CircleShape)
                .background(C.accent.copy(alpha = alpha)))
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
fun Avatar(emoji: String) {
    Box(Modifier.size(30.dp).clip(CircleShape).background(C.card), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 15.sp)
    }
}

@Composable
fun AndroidTextView(text: CharSequence) {
    androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
        android.widget.TextView(c).apply {
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#F2F5FA"))
            setLinkTextColor(android.graphics.Color.parseColor("#4F8CFF"))
        }
    }, update = { tv -> tv.text = text; tv.movementMethod =
        android.text.method.LinkMovementMethod.getInstance() })
}
