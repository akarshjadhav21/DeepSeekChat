package com.deepseek.chat.talk

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.deepseek.chat.MainActivity

class TalkTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .putExtra("open", "talk")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 12, i,
                PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
