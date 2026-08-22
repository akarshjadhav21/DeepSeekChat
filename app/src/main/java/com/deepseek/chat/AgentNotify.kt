package com.deepseek.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Heads-up notification so agent approvals requested via Talk voice are not missed
 *  while the user is away from the chat screen. Tapping opens the app on the chat. */
object AgentNotify {
    private const val CHANNEL_ID = "agent"
    private const val NOTIF_ID = 2002

    fun needsApproval(ctx: Context, what: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent approvals",
                    NotificationManager.IMPORTANCE_HIGH))
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val pi = PendingIntent.getActivity(ctx, 13,
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("open", "chat")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🤖 Approval needed")
                .setContentText(what.lineSequence().firstOrNull() ?: what)
                .setStyle(NotificationCompat.BigTextStyle().bigText(what.take(400)))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {
        }
    }

    fun clear(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }
}
