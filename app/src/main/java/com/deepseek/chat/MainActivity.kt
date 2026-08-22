package com.deepseek.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.deepseek.chat.ui.DeepSeekApp
import com.deepseek.chat.ui.DeepSeekTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getStringExtra("open") == "talk") {
            com.deepseek.chat.engine.AppStore.openTalk = true
        }
        if (intent?.getStringExtra("open") == "reports") {
            com.deepseek.chat.engine.AppStore.openReports = true
        }
        setContent {
            DeepSeekTheme {
                DeepSeekApp()
            }
        }
    }
}
