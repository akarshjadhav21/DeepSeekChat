package com.deepseek.chat

/**
 * Starter projects the Builder can scaffold into a brand-new GitHub repo.
 * "__D__" is replaced with a literal $ at assembly time so YAML/Kotlin dollar
 * syntax survives inside Kotlin raw strings.
 */
object BuilderTemplates {

    data class Tpl(
        val emoji: String,
        val name: String,
        val desc: String,
        val suggestedRepo: String,
        val files: List<Pair<String, String>> // path to content
    )

    private fun t(s: String) = s.replace("__D__", "$")

    // ---------- shared pieces ----------

    private fun settingsGradle(app: String) = """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "$app"
include(":app")
""".trim() + "\n"

    private val rootGradle = """
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
""".trim() + "\n"

    private fun manifest(app: String) = t("""
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="$app"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:allowBackup="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
""".trimIndent()) + "\n"

    private fun appGradle(pkg: String, compose: Boolean) = """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "$pkg"
    compileSdk = 34

    defaultConfig {
        applicationId = "$pkg"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        val ksFile = System.getenv("KEYSTORE_FILE")
        if (ksFile != null && java.io.File(ksFile).exists()) {
            create("ci") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("ci")?.let { signingConfig = it }
        }
    }${if (compose) """

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }""" else ""}

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
${if (compose) """    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")""" else """    implementation("androidx.core:core-ktx:1.13.1")"""}
}
""".trim() + "\n"

    private fun workflow(appNameSafe: String) = t("""
name: Build APK

on:
  push:
    branches: [ main ]
    tags: [ 'v*' ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle 8.7
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.7'

      - name: Decode release keystore (if secret present)
        if: __D__{{ env.KEYSTORE_B64 != '' }}
        env:
          KEYSTORE_B64: __D__{{ secrets.KEYSTORE_BASE64 }}
        run: echo "__D__KEYSTORE_B64" | base64 -d > /tmp/release.jks

      - name: Build APK
        run: gradle assembleDebug --no-daemon

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: __APPN__-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk

  release:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: build
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle 8.7
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.7'

      - name: Decode release keystore
        env:
          KEYSTORE_B64: __D__{{ secrets.KEYSTORE_BASE64 }}
        run: |
          if [ -z "__D__KEYSTORE_B64" ]; then echo "::error::Add KEYSTORE_* secrets for signed releases"; exit 1; fi
          echo "__D__KEYSTORE_B64" | base64 -d > /tmp/release.jks

      - name: Build signed release APK
        env:
          KEYSTORE_FILE: /tmp/release.jks
          KEYSTORE_PASSWORD: __D__{{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: __D__{{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: __D__{{ secrets.KEY_PASSWORD }}
        run: gradle assembleRelease --no-daemon

      - name: Create GitHub Release and upload signed APK
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/release/app-release.apk
          generate_release_notes: true
""".trimIndent().replace("__APPN__", appNameSafe)) + "\n"

    // ---------- template definitions ----------

    private fun helloCompose() = Tpl(
        emoji = "🚀",
        name = "Hello Compose",
        desc = "Material 3 Compose starter — tap counter. CI builds debug APK on every push; signed releases on v* tags.",
        suggestedRepo = "hello-compose",
        files = listOf(
            "settings.gradle.kts" to settingsGradle("hello"),
            "build.gradle.kts" to rootGradle,
            "app/build.gradle.kts" to appGradle("com.example.hello", true),
            "app/src/main/AndroidManifest.xml" to manifest("Hello"),
            "app/src/main/java/com/example/hello/MainActivity.kt" to t("""
package com.example.hello

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var n by remember { mutableStateOf(0) }
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Hello! 👋", fontSize = 28.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { n++ }) {
                            Text("Tapped __D__n times", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}
""".trimIndent()) + "\n",
            ".github/workflows/build.yml" to workflow("hello")
        )
    )

    private fun minimalTool() = Tpl(
        emoji = "🔧",
        name = "Minimal utility",
        desc = "No-Compose single-activity app — tiny & fast CI. Good base for small tools.",
        suggestedRepo = "mini-tool",
        files = listOf(
            "settings.gradle.kts" to settingsGradle("tool"),
            "build.gradle.kts" to rootGradle,
            "app/build.gradle.kts" to appGradle("com.example.tool", false),
            "app/src/main/AndroidManifest.xml" to manifest("Tool"),
            "app/src/main/java/com/example/tool/MainActivity.kt" to t("""
package com.example.tool

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var n = 0
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(245, 246, 250))
        }
        val title = TextView(this).apply {
            text = "Tool ready ✅"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        val btn = Button(this).apply {
            text = "Tap me"
            setOnClickListener {
                n++
                text = "Tapped __D__n times"
            }
        }
        box.addView(title)
        box.addView(btn)
        setContentView(box)
    }
}
""".trimIndent()) + "\n",
            ".github/workflows/build.yml" to workflow("tool")
        )
    )

    val all: List<Tpl> get() = listOf(helloCompose(), minimalTool())
}
