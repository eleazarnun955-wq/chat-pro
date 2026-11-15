plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.djsebas.chatpro"
    compileSdk = 35

    defaultConfig {
        // Keep this ID stable so future CHAT PRO builds update the same app.
        applicationId = "com.djsebas.chatpro.v150.testfix"
        minSdk = 23
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("CHAT_PRO_KEYSTORE_PATH") ?: "chat-pro-release.jks")
            storePassword = System.getenv("CHAT_PRO_STORE_PASSWORD")
            keyAlias = System.getenv("CHAT_PRO_KEY_ALIAS")
            keyPassword = System.getenv("CHAT_PRO_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Debug builds remain for local development only.
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
