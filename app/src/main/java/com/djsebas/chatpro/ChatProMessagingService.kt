package com.djsebas.chatpro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ChatProMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences("chatpro", Context.MODE_PRIVATE).edit()
            .putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.data["title"] ?: message.notification?.title ?: "CHAT PRO"
        val body = message.data["body"] ?: message.notification?.body ?: "Tienes una nueva notificación"
        val channelId = "chatpro_push"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Mensajes y llamadas", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("push_type", message.data["type"] ?: "message")
            putExtra("call_id", message.data["callId"] ?: "")
            putExtra("conversation_id", message.data["conversationId"] ?: "")
        }
        val pending = PendingIntent.getActivity(
            this, (message.data["callId"] ?: title).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
            else Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title).setContentText(body).setAutoCancel(true)
            .setContentIntent(pending).setPriority(Notification.PRIORITY_HIGH).build()
        manager.notify((message.data["callId"] ?: title + body).hashCode(), notification)
    }
}
