plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val firebaseApiKey = System.getenv("FIREBASE_API_KEY") ?: ""
val firebaseAppId = System.getenv("FIREBASE_APP_ID") ?: ""
val firebaseProjectId = System.getenv("FIREBASE_PROJECT_ID") ?: ""
val firebaseSenderId = System.getenv("FIREBASE_SENDER_ID") ?: ""

android {
    buildFeatures { buildConfig = true }
    namespace = "com.djsebas.chatpro"
    compileSdk = 35
    defaultConfig {
        // Independent install: CHAT PRO 2.2.1 installs independently alongside earlier versions.
        applicationId = "com.djsebas.chatpro.v221"
        // Firebase Messaging 26 requires Android 7.0 (API 24) or newer.
        minSdk = 24
        targetSdk = 35
        versionCode = 32
        versionName = "2.2.1"
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"$firebaseSenderId\"")
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("CHAT_PRO_KEYSTORE_PATH") ?: "chat-pro-release.jks"
            storeFile = file(keystorePath)
            storePassword = System.getenv("CHAT_PRO_STORE_PASSWORD") ?: providers.gradleProperty("CHAT_PRO_STORE_PASSWORD").orNull
            keyAlias = System.getenv("CHAT_PRO_KEY_ALIAS") ?: providers.gradleProperty("CHAT_PRO_KEY_ALIAS").orNull
            keyPassword = System.getenv("CHAT_PRO_KEY_PASSWORD") ?: providers.gradleProperty("CHAT_PRO_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        debug { }
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
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("io.socket:socket.io-client:2.1.1") { exclude(group = "org.json", module = "json") }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
}
