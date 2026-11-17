package com.djsebas.chatpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatProMessagingService : FirebaseMessagingService() {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    override fun onNewToken(token:String) {
        super.onNewToken(token)
        val prefs=getSharedPreferences("chatpro",Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token",token).apply()
        val auth=prefs.getString("token","") ?: ""
        val base=prefs.getString("server_url","https://chat-pro-server-qhjw.onrender.com") ?: "https://chat-pro-server-qhjw.onrender.com"
        if(auth.isBlank()) return
        Thread {
            try {
                val body=JSONObject().put("token",token).put("platform","android").toString().toRequestBody("application/json".toMediaType())
                val request=Request.Builder().url(base.trimEnd('/')+"/api/push/register").header("Authorization","Bearer "+auth).post(body).build()
                client.newCall(request).execute().close()
            } catch(_:Exception) { }
        }.start()
    }

    override fun onMessageReceived(message:RemoteMessage) {
        super.onMessageReceived(message)
        val title=message.notification?.title ?: message.data["title"] ?: "CHAT PRO"
        val body=message.notification?.body ?: message.data["body"] ?: "Tienes un mensaje nuevo"
        val channelId="chat_pro_messages"
        val manager=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId,"Mensajes de CHAT PRO",NotificationManager.IMPORTANCE_HIGH))
        }
        val intent=Intent(this,MainActivity::class.java).apply { flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=23)PendingIntent.FLAG_IMMUTABLE else 0)
        val notification=NotificationCompat.Builder(this,channelId)
            .setSmallIcon(R.drawable.chat_pro_icon).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pending).build()
        manager.notify((System.currentTimeMillis()%Int.MAX_VALUE).toInt(),notification)
    }
}
