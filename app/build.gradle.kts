plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.djsebas.chatpro"
    compileSdk = 35

    defaultConfig {
        // Stable application ID: keep CHAT PRO as the same Android app.
        applicationId = "com.djsebas.chatpro.v160"
        minSdk = 23
        targetSdk = 35
        versionCode = 11
        versionName = "1.6.1"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("CHAT_PRO_KEYSTORE_PATH")
                ?: "chat-pro-release.jks"
            storeFile = file(keystorePath)
            storePassword = System.getenv("CHAT_PRO_STORE_PASSWORD")
                ?: providers.gradleProperty("CHAT_PRO_STORE_PASSWORD").orNull
            keyAlias = System.getenv("CHAT_PRO_KEY_ALIAS")
                ?: providers.gradleProperty("CHAT_PRO_KEY_ALIAS").orNull
            keyPassword = System.getenv("CHAT_PRO_KEY_PASSWORD")
                ?: providers.gradleProperty("CHAT_PRO_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            // Debug builds are not distributed.
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
