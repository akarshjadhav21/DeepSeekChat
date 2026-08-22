package com.deepseek.chat.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.deepseek.chat.engine.AppStore
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val TALK_LANGS = listOf(
    "auto" to "🌐 Auto", "en-IN" to "English", "hi-IN" to "हिंदी",
    "kn-IN" to "ಕನ್ನಡ", "ta-IN" to "தமிழ்", "te-IN" to "తెలుగు",
    "bn-IN" to "বাংলা", "ml-IN" to "മലയാളം", "mr-IN" to "मराठी",
    "gu-IN" to "ગુજરાતી", "pa-IN" to "ਪੰਜਾਬੀ", "or-IN" to "ଓଡ଼ିଆ")

// Unicode script blocks -> BCP-47 tag, for picking the right TTS voice in auto mode
private val SCRIPT_LANGS = listOf(
    0x0900..0x097F to "hi-IN", 0x0980..0x09FF to "bn-IN", 0x0A00..0x0A7F to "pa-IN",
    0x0A80..0x0AFF to "gu-IN", 0x0B00..0x0B7F to "or-IN", 0x0B80..0x0BFF to "ta-IN",
    0x0C00..0x0C7F to "te-IN", 0x0C80..0x0CFF to "kn-IN", 0x0D00..0x0D7F to "ml-IN")

private fun detectScriptLang(text: String): String? {
    for ((r, tag) in SCRIPT_LANGS) {
        var hits = 0
        for (ch in text) { if (ch.code in r) { hits++; if (hits >= 3) return tag } }
    }
    return null
}

/** Selects the best installed TTS voice for a BCP-47 tag. Returns false if none found. */
private fun applyTtsVoice(t: TextToSpeech?, tag: String): Boolean {
    t ?: return false
    return try {
        val loc = Locale.forLanguageTag(tag)
        val voices = runCatching { t.voices }.getOrNull().orEmpty()
        val v = voices.firstOrNull { it.locale.language.equals(loc.language, true) &&
            it.locale.country.equals(loc.country, true) }
            ?: voices.firstOrNull { it.locale.language.equals(loc.language, true) }
        if (v != null && t.setVoice(v) == TextToSpeech.SUCCESS) return true
        var res = t.setLanguage(loc)
        if (res < TextToSpeech.LANG_AVAILABLE)
            res = t.setLanguage(Locale.forLanguageTag("en-IN"))
        res >= TextToSpeech.LANG_AVAILABLE
    } catch (_: Exception) { false }
}

/** Ring buffer of recent RMS values — drawn as circular waveform, zero recomposition. */
private class WaveBuf(val n: Int = 56) {
    val arr = FloatArray(n)
    var head = 0
    fun push(v: Float) { arr[head] = v; head = (head + 1) % n }
}

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
    var ttsReady by remember { mutableStateOf(false) }
    var awaitingReply by remember { mutableStateOf(false) }
    var ttsRef by remember { mutableStateOf<TextToSpeech?>(null) }
    var micGen by remember { mutableIntStateOf(0) }
    var srState by remember { mutableStateOf<SpeechRecognizer?>(null) }

    // RMS-reactive draw-only state: never read during composition -> no recomposition storms
    val rmsSmooth = remember { mutableFloatStateOf(0f) }
    val wave = remember { WaveBuf() }
    val waveTick = remember { mutableIntStateOf(0) }

    // keep screen on while talking — screen lock kills the recognizer mid-conversation
    DisposableEffect(Unit) {
        val a = ctx as? Activity
        a?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { a?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // ---------- recognizer lifecycle (recreated via micGen when stuck) ----------

    fun speechIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (lang != "auto") {
                // pinned language: no switching away
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            } else {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault().toLanguageTag())
                if (Build.VERSION.SDK_INT >= 33)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
            }
        }

    var listenGen by remember { mutableIntStateOf(0) }
    fun cancelScheduled() { listenGen++ }

    fun scheduleListen(delayMs: Long) {
        val gen = ++listenGen
        AppStore.handler.postDelayed({
            if (gen == listenGen && continuous && !AppStore.busy &&
                phase == "idle" && srState != null) {
                val s = srState
                if (s != null) try {
                    s.cancel()
                    s.startListening(speechIntent())
                } catch (_: Exception) { micGen++ }
            }
        }, delayMs)
    }

    fun startListening() {
        if (AppStore.busy || phase == "speaking") return
        val s = srState
        if (s == null) { note = "Speech recognition not available on this device"; return }
        try {
            s.cancel()
            s.startListening(speechIntent())
        } catch (_: Exception) { micGen++ }
    }

    fun afterSpeak() {
        phase = "idle"
        partial = ""
        // longer gap: restarting too fast makes the mic hear TTS tail echo
        if (continuous && !AppStore.busy) scheduleListen(700)
    }

    fun speakReply(text: String) {
        val clean = text.replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
            .replace(Regex("[#*`>_~\\[\\]]"), "").trim()
        val t = ttsRef
        if (!ttsOn || t == null || !ttsReady || clean.isBlank()) { afterSpeak(); return }
        // auto mode: speak the reply in the language its script actually is
        val targetTag = if (lang == "auto") detectScriptLang(clean) ?: "en-IN" else lang
        val voiceOk = applyTtsVoice(t, targetTag)
        phase = "speaking"
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
        if (!voiceOk) note = "No installed voice for $targetTag — spoken with default voice"
    }

    fun onHeard(text: String) {
        heard = text; partial = ""
        if (text.isBlank()) {
            phase = "idle"
            if (continuous && !AppStore.busy) scheduleListen(500)
            return
        }
        phase = "thinking"
        awaitingReply = true
        // fast model for snappy voice replies; blank falls back to the main chat model
        val talkModel = prefs.getString("talk_model", "")?.trim()?.ifBlank { null }
        AppStore.send(text, modelOverride = talkModel) {
            android.widget.Toast.makeText(ctx, "Set your API key in Settings",
                android.widget.Toast.LENGTH_SHORT).show()
            awaitingReply = false; phase = "idle"
        }
    }

    val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { phase = "listening"; note = ""; partial = "" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val v = rmsdB.coerceIn(0f, 12f)
            rmsSmooth.floatValue = rmsSmooth.floatValue * 0.6f + v * 0.4f
            wave.push(v)
            waveTick.intValue++
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            phase = "idle"; partial = ""
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    scheduleListen(500)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    micGen++               // rebuild the recognizer — it wedged itself
                    scheduleListen(1100)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    note = "Microphone permission needed"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    note = "That language isn't installed for voice input. Install it via " +
                        "Google app → Settings → Languages, or pick another pill."
                    micGen++               // no auto-retry into the same wall
                }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                    note = "Recognition rate-limited — retrying in 15 s"
                    scheduleListen(15000)
                }
                else -> { note = "Mic error $error — retrying"; scheduleListen(2000) }
            }
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
    }

    DisposableEffect(micGen) {
        val s = if (SpeechRecognizer.isRecognitionAvailable(ctx))
            try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { null }
        else null
        srState = s
        s?.setRecognitionListener(listener)
        onDispose { s?.setRecognitionListener(null); runCatching { s?.destroy() } }
    }

    DisposableEffect(Unit) {
        val t = TextToSpeech(ctx.applicationContext) { st ->
            AppStore.handler.post {
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) runCatching { applyTtsVoice(ttsRef, if (lang == "auto") "en-IN" else lang) }
            }
        }
        ttsRef = t
        onDispose { runCatching { t.stop(); t.shutdown() }; ttsRef = null }
    }

    LaunchedEffect(lang, ttsReady) {
        if (ttsReady) runCatching {
            applyTtsVoice(ttsRef, if (lang == "auto") "en-IN" else lang)
        }
    }

    // safety net: never hang in "thinking" forever (free-tier queue reality)
    LaunchedEffect(phase == "thinking") {
        if (phase != "thinking") return@LaunchedEffect
        delay(420_000)
        if (phase == "thinking") {
            awaitingReply = false
            AppStore.stopStreaming()
            note = "Model took too long — stopped waiting"
            phase = "idle"
        }
    }

    // when the model finishes, speak the newest assistant reply
    LaunchedEffect(AppStore.busy) {
        if (!AppStore.busy && awaitingReply && phase == "thinking") {
            awaitingReply = false
            val last = AppStore.active()?.msgs?.lastOrNull()
            if (AppStore.errorText != null) {
                note = AppStore.errorText ?: ""
                phase = "idle"
                if (continuous) scheduleListen(1500)
            } else if (last != null && last.role == "assistant") {
                speakReply(last.content)
            } else phase = "idle"
        }
    }

    val permGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) AppStore.handler.post { startListening() }
        else note = "Microphone permission denied"
    }

    // ---------- UI ----------
    val spinT = rememberInfiniteTransition(label = "orb")
    val spin by spinT.animateFloat(0f, 360f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val breathe by spinT.animateFloat(0.975f, 1.025f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe")

    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        if (pressed) 0.92f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "press")

    fun tapOrb() {
        cancelScheduled()
        when {
            phase == "speaking" -> { runCatching { ttsRef?.stop() }; afterSpeak() }
            phase == "listening" -> {
                // cancel, NOT stopListening — stop would deliver results and send half a sentence
                try { srState?.cancel() } catch (_: Exception) {}
                phase = "idle"; note = "Paused"
            }
            phase == "thinking" -> {
                AppStore.stopStreaming(); awaitingReply = false
                phase = "idle"; note = "Stopped"
            }
            permGranted -> startListening()
            else -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Talk", style = MaterialTheme.typography.titleLarge,
            color = C.textHi, modifier = Modifier.padding(bottom = 4.dp))
        Spacer(Modifier.height(14.dp))

        Box(Modifier.size(290.dp), contentAlignment = Alignment.Center) {
            // circular waveform + reactive rings — all drawn here, zero recomposition
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r0 = size.minDimension * 0.295f
                val listening = phase == "listening"
                val rmsNow = rmsSmooth.floatValue
                waveTick.intValue // read to invalidate on push
                for (k in 1..2) {
                    drawCircle(
                        color = C.accent.copy(alpha = (if (listening) 0.20f else 0.08f) / k),
                        radius = r0 * (1.05f + k * 0.11f) + rmsNow * 2.2f,
                        center = c, style = Stroke(width = 1.5.dp.toPx()))
                }
                val n = wave.n
                val stepA = (2.0 * PI / n)
                for (i in 0 until n) {
                    val v = wave.arr[(wave.head + i) % n]
                    val a = i * stepA + Math.toRadians(spin.toDouble())
                    val idleLen = 3.dp.toPx()
                    val len = idleLen + v * 9.dp.toPx() * (if (listening) 1f else 0.45f)
                    val sx = c.x + cos(a).toFloat() * r0
                    val sy = c.y + sin(a).toFloat() * r0
                    val ex = c.x + cos(a).toFloat() * (r0 + len)
                    val ey = c.y + sin(a).toFloat() * (r0 + len)
                    drawLine(
                        color = lerp(C.accent, C.accent2, i.toFloat() / n),
                        start = androidx.compose.ui.geometry.Offset(sx, sy),
                        end = androidx.compose.ui.geometry.Offset(ex, ey),
                        strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }

            // rotating gradient halo behind the orb (GPU-cheap, draw-phase only)
            Box(Modifier.size(196.dp).graphicsLayer {
                rotationZ = spin; alpha = 0.55f
            }.clip(CircleShape).background(Brush.sweepGradient(
                listOf(C.accent, C.accent2, Color(0x00000000), C.accent))))

            // orb core — scale changes happen in graphicsLayer (no relayout)
            Box(Modifier.size(150.dp).graphicsLayer {
                val b = if (phase == "listening") breathe *
                    (1f + rmsSmooth.floatValue * 0.006f) else 1f
                scaleX = b * pressScale; scaleY = b * pressScale
            }.clip(CircleShape).background(Brush.linearGradient(listOf(C.accent, C.accent2)))
                .border(2.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try { awaitRelease() } catch (_: Exception) {}
                            pressed = false
                        },
                        onTap = { tapOrb() })
                },
                contentAlignment = Alignment.Center) {
                Crossfade(targetState = phase, label = "phaseIcon") { p ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when (p) {
                            "listening" -> Icon(Icons.Filled.Mic, null, tint = Color.White,
                                modifier = Modifier.size(54.dp))
                            "thinking" -> Text("⏳", fontSize = 40.sp)
                            "speaking" -> Text("🔊", fontSize = 44.sp)
                            else -> Icon(Icons.Filled.Mic, null,
                                tint = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.size(46.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (phase == "thinking") {
            var secs by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(1000); secs++ } }
            Text("⏳ Thinking… ${secs}s — slow models queue for minutes",
                color = C.textMid, fontSize = 13.sp, textAlign = TextAlign.Center)
        } else {
            Text(when (phase) {
                "listening" -> "🎙 Listening — speak now"
                "speaking" -> "🔊 Tap orb to interrupt"
                else -> note.ifBlank { "Tap the orb and speak" }
            }, color = C.textMid, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

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

        Spacer(Modifier.height(16.dp))
        Text("Language", color = C.textMid, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((tag, label) in TALK_LANGS) {
                FilterChip(selected = lang == tag,
                    onClick = {
                        lang = tag
                        prefs.edit().putString("talk_lang", tag).apply()
                        runCatching {
                            applyTtsVoice(ttsRef, if (tag == "auto") "en-IN" else tag)
                        }
                    },
                    label = { Text(label, fontSize = 11.sp) }, shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = C.accent.copy(alpha = 0.28f),
                        selectedLabelColor = C.textHi))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = continuous, onCheckedChange = {
                    continuous = it
                    prefs.edit().putBoolean("talk_continuous", it).apply()
                })
                Text(" 🔄 Hands-free", color = C.textMid, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = ttsOn, onCheckedChange = {
                    ttsOn = it
                    prefs.edit().putBoolean("talk_tts", it).apply()
                    if (!it) runCatching { ttsRef?.stop() }
                })
                Text(" 🔊 Speak replies", color = C.textMid, fontSize = 12.sp)
            }
        }
        TextButton(onClick = {
            micGen++
            note = "Mic service reset — tap the orb"
            phase = "idle"
        }) { Text("🩺 Mic acting up? Reset it", color = C.textLow, fontSize = 11.sp) }
    }
}
