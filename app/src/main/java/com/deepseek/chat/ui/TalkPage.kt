package com.deepseek.chat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.deepseek.chat.engine.AppStore
import java.util.Locale

private val TALK_LANGS = listOf(
    "auto" to "🌐 Auto", "en-IN" to "English", "hi-IN" to "हिंदी",
    "kn-IN" to "ಕನ್ನಡ", "ta-IN" to "தமிழ்", "te-IN" to "తెలుగు",
    "bn-IN" to "বাংলা", "ml-IN" to "മലയാളം", "mr-IN" to "मराठी",
    "gu-IN" to "ગુજરાતી", "pa-IN" to "ਪੰਜਾਬੀ", "or-IN" to "ଓଡ଼ିଆ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TalkPage() {
    val ctx = LocalContext.current
    val prefs = AppStore.prefs()

    var lang by remember { mutableStateOf(prefs.getString("talk_lang", "auto") ?: "auto") }
    var continuous by remember { mutableStateOf(prefs.getBoolean("talk_continuous", true)) }
    var ttsOn by remember { mutableStateOf(prefs.getBoolean("talk_tts", true)) }
    var phase by remember { mutableStateOf("idle") } // idle|listening|thinking|speaking
    var partial by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("Tap the orb and speak") }
    var rms by remember { mutableStateOf(0f) }
    var ttsReady by remember { mutableStateOf(false) }
    var awaitingReply by remember { mutableStateOf(false) }
    var ttsRef by remember { mutableStateOf<TextToSpeech?>(null) }

    // ---------- speech recognizer ----------
    val sr = remember {
        if (SpeechRecognizer.isRecognitionAvailable(ctx))
            SpeechRecognizer.createSpeechRecognizer(ctx) else null
    }

    fun speechIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (lang != "auto") putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            if (lang == "auto" && Build.VERSION.SDK_INT >= 33)
                putExtra(SpeechRecognizer.EXTRA_LANGUAGE_SWITCH, true)
        }

    fun startListening() {
        if (sr == null) { note = "Speech recognition not available on this device"; return }
        if (AppStore.busy || phase == "speaking") return
        try { sr.startListening(speechIntent()) } catch (_: Exception) {}
    }

    fun afterSpeak() {
        phase = "idle"
        partial = ""
        if (continuous && !AppStore.busy)
            AppStore.handler.postDelayed({ if (phase == "idle") startListening() }, 400)
    }

    fun speakReply(text: String) {
        val clean = text.replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
            .replace(Regex("[#*`>_~\\[\\]]"), "").trim()
        val t = ttsRef
        if (!ttsOn || t == null || !ttsReady || clean.isBlank()) { afterSpeak(); return }
        phase = "speaking"
        runCatching {
            t.language = Locale.forLanguageTag(if (lang == "auto") "en" else lang)
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                AppStore.handler.post { if (phase == "speaking") afterSpeak() }
            }
            override fun onError(id: String?) {
                AppStore.handler.post { if (phase == "speaking") afterSpeak() }
            }
        })
        try { t.speak(clean.take(900), TextToSpeech.QUEUE_FLUSH, null, "reply") }
        catch (_: Exception) { afterSpeak() }
    }

    DisposableEffect(Unit) {
        val t = TextToSpeech(ctx.applicationContext) { st ->
            AppStore.handler.post {
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) runCatching {
                    ttsRef?.setLanguage(Locale.forLanguageTag(if (lang == "auto") "en" else lang))
                }
            }
        }
        ttsRef = t
        onDispose { runCatching { t.stop(); t.shutdown() }; ttsRef = null }
    }

    LaunchedEffect(lang, ttsReady) {
        if (ttsReady) runCatching {
            ttsRef?.setLanguage(Locale.forLanguageTag(if (lang == "auto") "en" else lang))
        }
    }

    fun onHeard(text: String) {
        heard = text; partial = ""
        if (text.isBlank()) {
            phase = "idle"
            if (continuous && !AppStore.busy) AppStore.handler.postDelayed({
                if (phase == "idle") startListening() }, 500)
            return
        }
        phase = "thinking"
        awaitingReply = true
        AppStore.send(text) {
            android.widget.Toast.makeText(ctx, "Set your API key in Settings",
                android.widget.Toast.LENGTH_SHORT).show()
            awaitingReply = false; phase = "idle"
        }
    }

    DisposableEffect(sr) {
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { phase = "listening"; note = ""; partial = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { rms = rmsdB.coerceIn(0f, 12f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                phase = "idle"
                note = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    else -> "Mic error $error — tap to retry"
                }
                if (note.isEmpty() && continuous && !AppStore.busy)
                    AppStore.handler.postDelayed({ if (phase == "idle") startListening() }, 600)
            }
            override fun onResults(results: Bundle?) {
                val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: ""
                onHeard(txt)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { runCatching { sr?.stopListening(); sr?.destroy() } }
    }

    val permGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) AppStore.handler.post { startListening() }
        else note = "Microphone permission denied"
    }

    // when the model finishes, speak the newest assistant reply
    LaunchedEffect(AppStore.busy) {
        if (!AppStore.busy && awaitingReply && phase == "thinking") {
            awaitingReply = false
            val last = AppStore.active()?.msgs?.lastOrNull()
            if (AppStore.errorText != null) {
                note = AppStore.errorText ?: ""
                phase = "idle"
                if (continuous) AppStore.handler.postDelayed({
                    if (phase == "idle") startListening() }, 1500)
            } else if (last != null && last.role == "assistant") {
                speakReply(last.content)
            } else phase = "idle"
        }
    }

    // ---------- UI ----------
    val pulse = rememberInfiniteTransition(label = "orb")
    val breathe by pulse.animateFloat(0.94f, 1.06f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing),
            RepeatMode.Reverse), label = "breathe")
    val ring = (1f + rms / 6f).coerceAtMost(2f)

    fun tapOrb() {
        when {
            phase == "speaking" -> { runCatching { ttsRef?.stop() }; afterSpeak() }
            phase == "listening" -> { sr?.stopListening(); phase = "idle"; note = "Paused" }
            permGranted -> startListening()
            else -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Talk", style = MaterialTheme.typography.titleLarge,
            color = C.textHi, modifier = Modifier.padding(bottom = 4.dp))
        Spacer(Modifier.height(18.dp))

        Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
            // outer reactive ring
            Box(Modifier.size((140 * ring).dp).graphicsLayer(alpha = 0.25f)
                .clip(CircleShape).background(C.accent))
            // orb
            Box(Modifier.size(130.dp).scale(if (phase == "listening") breathe else 1f)
                .clip(CircleShape).background(Brush.linearGradient(
                    listOf(C.accent, C.accent2)))
                .clickable { tapOrb() },
                contentAlignment = Alignment.Center) {
                when (phase) {
                    "listening" -> Icon(Icons.Filled.Mic, null, tint = Color.White,
                        modifier = Modifier.size(52.dp))
                    "thinking" -> TypingDots()
                    "speaking" -> Text("🔊", fontSize = 44.sp)
                    else -> Icon(Icons.Filled.Mic, null, tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(46.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(when (phase) {
            "listening" -> "🎙 Listening — speak now"
            "thinking" -> "⏳ Thinking…"
            "speaking" -> "🔊 Tap orb to interrupt"
            else -> note.ifBlank { "Tap the orb and speak" }
        }, color = C.textMid, fontSize = 13.sp, textAlign = TextAlign.Center)

        if (partial.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("“$partial”", color = C.textHi, fontSize = 16.sp,
                textAlign = TextAlign.Center)
        }
        if (heard.isNotBlank() && phase == "idle") {
            Spacer(Modifier.height(4.dp))
            Text("You said: $heard", color = C.textLow, fontSize = 12.sp,
                textAlign = TextAlign.Center, maxLines = 2)
        }

        Spacer(Modifier.height(18.dp))
        Text("Language", color = C.textMid, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((tag, label) in TALK_LANGS) {
                FilterChip(selected = lang == tag,
                    onClick = { lang = tag
                        prefs.edit().putString("talk_lang", tag).apply()
                        runCatching { ttsRef?.setLanguage(
                            Locale.forLanguageTag(if (tag == "auto") "en" else tag)) }
                    },
                    label = { Text(label, fontSize = 11.sp) }, shape = CircleShape)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = continuous, onCheckedChange = { continuous = it
                    prefs.edit().putBoolean("talk_continuous", it).apply() })
                Text(" 🔄 Hands-free", color = C.textMid, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = ttsOn, onCheckedChange = { ttsOn = it
                    prefs.edit().putBoolean("talk_tts", it).apply()
                    if (!it) runCatching { ttsRef?.stop() } })
                Text(" 🔊 Speak replies", color = C.textMid, fontSize = 12.sp)
            }
        }
    }
}
