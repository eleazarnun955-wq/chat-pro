package com.djsebas.chatpro

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class ChatProApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isNotEmpty()) return
        val apiKey = BuildConfig.FIREBASE_API_KEY
        val appId = BuildConfig.FIREBASE_APP_ID
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        val senderId = BuildConfig.FIREBASE_SENDER_ID
        if (apiKey.isBlank() || appId.isBlank() || projectId.isBlank() || senderId.isBlank()) return
        val options = FirebaseOptions.Builder()
            .setApiKey(apiKey)
            .setApplicationId(appId)
            .setProjectId(projectId)
            .setGcmSenderId(senderId)
            .build()
        FirebaseApp.initializeApp(this, options)
    }
}
