package com.deepseek.chat.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deepseek.chat.engine.AppStore

@Composable
fun DeepSeekApp() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        AppStore.init(ctx)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()) {
                ctx.startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName)))
            }
        } catch (_: Exception) {}
    }
    // one-shot intents from agent (install/uninstall dialogs)
    LaunchedEffect(AppStore.intentEvent) {
        AppStore.intentEvent?.let { i ->
            try { ctx.startActivity(i) } catch (_: Exception) {}
            AppStore.intentEvent = null
        }
    }

    val nav = rememberNavController()
    LaunchedEffect(Unit) {
        if (AppStore.openTalk) { nav.navigate("talk"); AppStore.openTalk = false }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "chats"
    val showBar = route in listOf("chats", "talk", "build", "models", "settings")

    Scaffold(
        bottomBar = {
            AnimatedVisibility(showBar, enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()) {
                NavigationBar(containerColor = C.surface) {
                    val items = listOf(
                        Triple("chats", Icons.Filled.Forum, "Chats"),
                        Triple("talk", Icons.Filled.Mic, "Talk"),
                        Triple("build", Icons.Filled.Build, "Build"),
                        Triple("models", Icons.Filled.Psychology, "Models"),
                        Triple("settings", Icons.Filled.Settings, "Settings"))
                    for ((r, icon, label) in items) {
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                if (route != r) nav.navigate(r) {
                                    popUpTo("chats") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, null) },
                            label = { Text(label) })
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).background(C.bg)) {
            NavHost(nav, startDestination = "chats",
                enterTransition = { slideInHorizontally { it / 3 } + fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { slideOutHorizontally { it / 3 } + fadeOut() }) {
                composable("chats") { ChatsListPage(onOpen = { id -> nav.navigate("conv/$id") }) }
                composable("conv/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    ConversationPage(it.arguments?.getString("id") ?: "",
                        onBack = { nav.popBackStack() },
                        onNeedSettings = { nav.navigate("settings") })
                }
                composable("talk") { TalkPage() }
                composable("build") { BuilderPage() }
                composable("models") { ModelsPage() }
                composable("settings") { SettingsPage() }
            }
        }
    }
}
