package com.deepseek.chat.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF0B0F14)
    val surface = Color(0xFF12161D)
    val card = Color(0xFF171C24)
    val accent = Color(0xFF4F8CFF)
    val accent2 = Color(0xFF9B6BFF)
    val green = Color(0xFF37D67A)
    val red = Color(0xFFFF5C5C)
    val amber = Color(0xFFFFC24B)
    val textHi = Color(0xFFF2F5FA)
    val textMid = Color(0xFF9AA7B8)
    val textLow = Color(0xFF5C6878)
    val bubbleUser = Brush.horizontalGradient(listOf(Color(0xFF2A62D4), Color(0xFF4F8CFF)))
    val bubbleAi = Color(0xFF1A212C)
    val toolBg = Color(0xFF0E1512)

    fun userBubble() = Brush.horizontalGradient(listOf(Color(0xFF2A62D4), Color(0xFF4F8CFF)))
}

@Composable
fun DeepSeekTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            secondary = C.accent2,
            background = C.bg,
            surface = C.surface,
            surfaceVariant = C.card,
            error = C.red
        ),
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            bodyMedium = TextStyle(fontSize = 15.sp),
            labelSmall = TextStyle(fontSize = 11.sp, color = C.textMid)
        ),
        content = content
    )
}

fun mono() = FontFamily.Monospace

enum class Page(val label: String, val route: String) {
    Chats("Chats", "chats"),
    Builder("Build", "build"),
    Models("Models", "models"),
    Settings("Settings", "settings")
}
