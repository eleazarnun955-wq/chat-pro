plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.djsebas.chatpro"
    compileSdk = 35

    defaultConfig {
        // Unique package keeps this build separate from earlier CHAT PRO installs.
        // Keep the previous app installed; users can sign in to the same server account.
        applicationId = "com.djsebas.chatpro.v182"
        minSdk = 23
        targetSdk = 35
        versionCode = 25
        versionName = "1.8.2"
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
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
}
