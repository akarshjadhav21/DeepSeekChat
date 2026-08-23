package com.deepseek.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepseek.chat.engine.AppStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v3.8 — background device watchers.
 * Rules like "battery < 20%" evaluated every N minutes by WorkManager;
 * each rule fires ONCE per trip (edge-triggered) → AI alert line → 📊 chat + notification.
 */
data class WatchRule(
    val id: String,
    val metric: String,   // battery | storage | ram
    val op: String,       // "<" or ">"
    val value: Int,       // percent threshold
    var enabled: Boolean = true,
    var triggered: Boolean = false
)

class WatcherWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        Watchers.evaluate(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

object Watchers {

    const val WORK_NAME = "device_watchers"
    const val CHAT_TITLE = "👀 Watchers"
    private const val KEY_RULES = "watchers_json"
    private const val CHANNEL_ID = "watchers"

    fun prefs(ctx: Context) =
        ctx.getSharedPreferences("dsprefs", Context.MODE_PRIVATE)

    // ---------- persistence ----------

    fun load(ctx: Context): List<WatchRule> {
        return try {
            val arr = JSONArray(prefs(ctx).getString(KEY_RULES, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WatchRule(
                    id = o.getString("id"),
                    metric = o.getString("metric"),
                    op = o.optString("op", "<"),
                    value = o.optInt("value", 20),
                    enabled = o.optBoolean("enabled", true),
                    triggered = o.optBoolean("triggered", false))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, rules: List<WatchRule>) {
        val arr = JSONArray()
        for (r in rules) arr.put(JSONObject()
            .put("id", r.id).put("metric", r.metric).put("op", r.op)
            .put("value", r.value).put("enabled", r.enabled).put("triggered", r.triggered))
        prefs(ctx).edit().putString(KEY_RULES, arr.toString()).commit()
    }

    // ---------- scheduling ----------

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("watch_enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("watch_enabled", on).commit()
        if (on) schedule(ctx, prefs(ctx).getInt("watch_interval_min", 30))
        else cancel(ctx)
    }

    fun schedule(ctx: Context, minutes: Int) {
        val mins = minutes.coerceAtLeast(15)   // WorkManager periodic floor
        prefs(ctx).edit().putInt("watch_interval_min", mins).commit()
        val req = PeriodicWorkRequestBuilder<WatcherWorker>(mins.toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    // ---------- metrics (app UID, no root) ----------

    fun metrics(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        runCatching {
            val o = Agent.execute("dumpsys battery | grep -E 'level|powered'")
            Regex("level:\\s*(\\d+)").find(o)?.groupValues?.get(1)?.toInt()?.let { out["battery"] = it }
            out["charging"] = if (o.contains("powered: true")) 1 else 0
        }
        runCatching {
            // toybox df -k: Filesystem 1K-blocks Used Available Use% Mounted on
            val cols = Agent.execute("df -k /sdcard | tail -1").trim().split(Regex("\\s+"))
            val total = cols.getOrNull(1)?.toLongOrNull()
            val used = cols.getOrNull(2)?.toLongOrNull()
            if (total != null && used != null && total > 0 && used in 0..total * 4)
                out["storage"] = (used * 100 / total).toInt().coerceIn(0, 100)
        }
        runCatching {
            val o = Agent.execute("cat /proc/meminfo | head -3")
            val total = Regex("MemTotal:\\s*(\\d+)").find(o)?.groupValues?.get(1)?.toLongOrNull()
            val avail = Regex("MemAvailable:\\s*(\\d+)").find(o)?.groupValues?.get(1)?.toLongOrNull()
            if (total != null && avail != null && total > 0)
                out["ram"] = ((total - avail) * 100 / total).toInt()
        }
        return out
    }

    fun describe(metric: String): String = when (metric) {
        "battery" -> "🔋 Battery"
        "storage" -> "💾 Storage used"
        "ram" -> "🧠 RAM used"
        else -> metric
    }

    fun ruleText(r: WatchRule) = "${describe(r.metric)} ${r.op} ${r.value}%"

    // ---------- evaluation ----------

    fun evaluate(ctx: Context) {
        val rules = load(ctx)
        val active = rules.filter { it.enabled }
        if (active.isEmpty()) return
        val m = metrics()
        if (m.isEmpty()) return
        var changed = false
        for (r in active) {
            val v = m[r.metric] ?: continue
            val violated = if (r.op == "<") v < r.value else v > r.value
            if (violated && !r.triggered) {
                r.triggered = true; changed = true
                fire(ctx, r, v, m)
            } else if (!violated && r.triggered) {
                r.triggered = false; changed = true   // recovered — silent reset
            }
        }
        if (changed) save(ctx, rules)
    }

    private fun fire(ctx: Context, r: WatchRule, actual: Int, all: Map<String, Int>) {
        val raw = buildString {
            append("Condition: ${ruleText(r)} just became true (actual value: $actual%). ")
            append("Current stats: ")
            append(all.filterKeys { it != "charging" }.entries.joinToString(", ") {
                "${it.key} ${it.value}%" })
            if (all["charging"] == 1) append(". Phone is charging")
            append(".")
        }
        val prefs = prefs(ctx)
        var summary = ""
        try {
            summary = NviClient.complete(
                key = prefs.getString("api_key", "") ?: "",
                model = prefs.getString("model", NviClient.DEFAULT_MODEL) ?: NviClient.DEFAULT_MODEL,
                base = prefs.getString("base_url", NviClient.DEFAULT_BASE) ?: NviClient.DEFAULT_BASE,
                prompt = "You are a phone assistant. A background watcher tripped. Write ONE short " +
                    "friendly alert (max 25 words): state what happened and give one practical " +
                    "suggestion. Facts: $raw",
                maxTokens = 150)
        } catch (_: Exception) {
        }
        val ts = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date())
        val head = "👀 ${describe(r.metric)} ${r.op} ${r.value}% — now $actual%"
        val body = "**$head** · $ts\n\n${summary.ifBlank { "Watcher tripped! $raw" }}"
        deliver(ctx, "$head", body, r.id)
    }

    // ---------- delivery (mirrors Reports pattern) ----------

    private fun deliver(ctx: Context, notifTitle: String, body: String, ruleId: String) {
        synchronized(ChatStore.ioLock) {
            val chats = ChatStore.list(ctx)
            val target = chats.firstOrNull { it.title == CHAT_TITLE } ?: run {
                val c = Chat(java.util.UUID.randomUUID().toString(), CHAT_TITLE)
                chats.add(0, c); c
            }
            target.msgs.add(Msg("assistant", body))
            ChatStore.saveAll(ctx, chats)
        }
        AppStore.handler.post { if (AppStore.ready) AppStore.reload() }
        notify(ctx, notifTitle, body, ruleId)
    }

    private fun notify(ctx: Context, title: String, body: String, ruleId: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Device watchers",
                    NotificationManager.IMPORTANCE_DEFAULT))
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val pi = PendingIntent.getActivity(ctx, 14,
                Intent(ctx, MainActivity::class.java).apply {
                    putExtra("open", "chat")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(2100 + (ruleId.hashCode() % 500),
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(body.replace(Regex("[*`#]"), "").lineSequence()
                        .drop(1).firstOrNull { it.isNotBlank() }?.take(90) ?: "")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build())
        } catch (_: Exception) {
        }
    }
}
