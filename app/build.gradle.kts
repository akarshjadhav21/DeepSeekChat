plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deepseek.chat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deepseek.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.7"
    }

    signingConfigs {
        val ksFile = System.getenv("KEYSTORE_FILE")
        val ksPass = System.getenv("KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("KEY_ALIAS")
        val keyPass = System.getenv("KEY_PASSWORD")
        if (ksFile != null && file(ksFile).exists()) {
            create("ciRelease") {
                storeFile = file(ksFile)
                storePassword = ksPass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ci = signingConfigs.findByName("ciRelease")
            signingConfig = if (ci != null) ci else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.core:core:1.13.1")
}
