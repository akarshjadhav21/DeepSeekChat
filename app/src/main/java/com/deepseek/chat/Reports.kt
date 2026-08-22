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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class ReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        Reports.gatherAndDeliver(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

object Reports {

    const val WORK_NAME = "scheduled_reports"
    const val CHAT_TITLE = "📊 Scheduled reports"
    private const val CHANNEL_ID = "reports"
    private const val NOTIF_ID = 2001

    private val RECIPES = listOf(
        "battery" to "dumpsys battery | grep -E 'level|status|powered'",
        "storage" to "df -h /sdcard | head -3",
        "memory" to "cat /proc/meminfo | head -3",
        "uptime" to "uptime"
    )

    fun schedule(ctx: Context, hours: Int) {
        val req = PeriodicWorkRequestBuilder<ReportWorker>(hours.toLong(), TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    /** Collects stats, asks the model for a friendly summary, stores as a chat + notification. */
    fun gatherAndDeliver(ctx: Context) {
        val stats = RECIPES.joinToString("\n\n") { (name, cmd) ->
            "# $name\n" + Agent.quoteForChat(cmd, Agent.execute(cmd))
        }
        // read prefs directly — WorkManager may run before AppStore.init ever happened
        val prefs = ctx.getSharedPreferences("dsprefs", Context.MODE_PRIVATE)
        var summary = ""
        try {
            summary = NviClient.complete(
                key = prefs.getString("api_key", "") ?: "",
                model = prefs.getString("model", NviClient.DEFAULT_MODEL) ?: NviClient.DEFAULT_MODEL,
                base = prefs.getString("base_url", NviClient.DEFAULT_BASE) ?: NviClient.DEFAULT_BASE,
                prompt = "You are a device health reporter. Turn these raw Android stats into a short " +
                    "friendly report (max 120 words, emoji bullets, flag anything unusual). " +
                    "Raw data:\n$stats",
                maxTokens = 500)
        } catch (_: Exception) {
        }
        val ts = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.getDefault()).format(Date())
        val report = "**📊 Device report** · $ts\n\n" +
            summary.ifBlank { "Model unreachable — raw stats:\n```\n$stats\n```" }
        deliver(ctx, report)
    }

    private fun deliver(ctx: Context, report: String) {
        val chats = ChatStore.list(ctx)
        val target = chats.firstOrNull { it.title == CHAT_TITLE } ?: run {
            val c = Chat(UUID.randomUUID().toString(), CHAT_TITLE)
            chats.add(0, c)
            c
        }
        target.msgs.add(Msg("assistant", report))
        ChatStore.saveAll(ctx, chats)
        AppStore.handler.post { if (AppStore.ready) AppStore.reload() }
        notify(ctx, report)
    }

    private fun notify(ctx: Context, report: String) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Scheduled reports", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val pi = PendingIntent.getActivity(ctx, 12,
                Intent(ctx, MainActivity::class.java).apply {
                    putExtra("open", "reports")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("📊 Device report ready")
                .setContentText(report.replace(Regex("[*`#]"), "").lineSequence()
                    .lastOrNull { it.isNotBlank() }?.take(80) ?: "")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }
}
