package com.djsebas.chatpro

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import io.socket.client.IO
import io.socket.client.Socket
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : FragmentActivity() {
    private val bg=Color.rgb(17,27,33)
    private val panel=Color.rgb(32,44,51)
    private var accent=Color.rgb(37,211,102)
    private val http=OkHttpClient.Builder().connectTimeout(25,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).build()
    private val defaultBaseUrl="https://chat-pro-server-2-2-0.onrender.com"
    private var baseUrl=defaultBaseUrl
    private var token=""
    private var me=JSONObject()
    private var currentConversation=""
    private var currentTitle=""
    private var currentPeerId=""
    private var uploadMode="chat"
    private var pendingStatusCaption=""
    private var pendingWallpaperKey="home_wallpaper"
    private var uiScale=1
    private var socket:Socket?=null
    private var callSocket:Socket?=null
    private var audioPlayer:MediaPlayer?=null
    private var voiceRecorder:MediaRecorder?=null
    private var voiceFile:File?=null
    private var isRecordingVoice=false
    private var currentConversationType="direct"
    private val reactionViews=mutableMapOf<String,TextView>()
    private var homeTab="Chats"
    private var homeSubPage=""
    private var backAction:(()->Unit)?=null
    private var appNeedsUnlock=false
    private val notificationChannelId="chatpro_messages"
    private lateinit var root:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var header:TextView
    private lateinit var info:TextView
    private lateinit var composer:EditText

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        val p=getSharedPreferences("chatpro",MODE_PRIVATE)
        val savedServer=p.getString("server_url",defaultBaseUrl)?:defaultBaseUrl
        baseUrl=try { normalizeServerUrl(savedServer) } catch (_:Exception) {
            p.edit().remove("server_url").apply()
            defaultBaseUrl
        }
        accent=p.getInt("accent_color",Color.rgb(37,211,102))
        uiScale=p.getInt("ui_scale",1).coerceIn(0,2)
        token=readSecureToken()
        setupMessageNotifications()
        if(token.isNotBlank()) {
            val savedPin=p.getString("app_pin","")?:""
            if(savedPin.isNotBlank()) showSecurityGate { loadMe() } else loadMe()
        } else showAuth()
    }

    private fun frame(title:String,subtitle:String="") {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(14,10,14,10) }
        if(backAction!=null) {
            val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
            nav.addView(action("←  Atrás") {
                val goBack=backAction
                backAction=null
                goBack?.invoke()
            },LinearLayout.LayoutParams(-2,48))
            root.addView(nav)
        }
        root.addView(TextView(this).apply { text=title;textSize=25f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setPadding(4,8,4,2) })
        if(subtitle.isNotBlank())root.addView(TextView(this).apply{text=subtitle;textSize=12f;gravity=Gravity.CENTER;setTextColor(accent);setPadding(4,0,4,8)})
        header=TextView(this).apply{text="";textSize=13f;setTextColor(Color.LTGRAY);setPadding(4,6,4,6)}
        root.addView(header)
        val scroll=ScrollView(this)
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(2,4,2,10)}
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        applyWallpaper("home_wallpaper")
    }
    private fun showAuth() {
        frame("CHAT PRO","Mensajería privada · cuentas y perfiles")
        val name=field("Nombre visible")
        val email=field("Correo electrónico")
        email.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val password=field("Contraseña (mínimo 8 caracteres)")
        password.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        content.addView(name);content.addView(email);content.addView(passwordField(password))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(action("Crear cuenta"){authRequest("/api/register",JSONObject().put("name",name.text.toString()).put("email",email.text.toString()).put("password",password.text.toString()))},LinearLayout.LayoutParams(0,54,1f))
        actions.addView(action("Iniciar sesión"){authRequest("/api/login",JSONObject().put("email",email.text.toString()).put("password",password.text.toString()))},LinearLayout.LayoutParams(0,54,1f))
        content.addView(actions)
        content.addView(action("Configurar servidor"){configureServer()})
        header.text="Regístrate e inicia sesión únicamente con tu correo electrónico y contraseña."
    }
    private fun authRequest(path:String,body:JSONObject) {
        header.text="Conectando…"
        Thread {
            try {
                val req=Request.Builder().url(baseUrl+path).post(body.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                http.newCall(req).execute().use { res ->
                    val data=JSONObject(res.body?.string()?: "{}")
                    if(!res.isSuccessful)throw Exception(data.optString("error","HTTP ${res.code}"))
                    token=data.optString("token");me=data.optJSONObject("user")?:JSONObject()
                    saveSecureToken(token)
                    runOnUiThread{showHome("Chats")}
                }
            } catch(e:Exception){runOnUiThread{header.text=e.message?: "No se pudo iniciar sesión"}}
        }.start()
    }
    private fun loadMe() {
        api("GET","/api/me",null,{data->me=data.optJSONObject("user")?:JSONObject();showHome("Chats")},{showAuth()})
    }
    private fun api(method:String,path:String,body:JSONObject?,ok:(JSONObject)->Unit,fail:(String)->Unit) {
        Thread {
            try {
                val b=Request.Builder().url(baseUrl+path).header("Authorization","Bearer "+token)
                val req=when(method){
                    "POST"->b.post((body?:JSONObject()).toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                    "PATCH"->b.patch((body?:JSONObject()).toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                    "DELETE"->b.delete().build()
                    else->b.get().build()
                }
                http.newCall(req).execute().use{res->
                    val raw=res.body?.string()?: "{}";val data=try{JSONObject(raw)}catch(_:Exception){JSONObject()}
                    if(!res.isSuccessful)throw Exception(data.optString("error","HTTP ${res.code}"))
                    runOnUiThread{ok(data)}
                }
            }catch(e:Exception){runOnUiThread{fail(e.message?:"Error de conexión")}}
        }.start()
    }
    private fun tokenKey():javax.crypto.SecretKey {
        val alias="chatpro_session_key_v220"
        val store=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val existing=store.getKey(alias,null) as? javax.crypto.SecretKey
        if(existing!=null)return existing
        val generator=javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        val spec=android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build()
        generator.init(spec)
        return generator.generateKey()
    }
    private fun saveSecureToken(value:String) {
        try {
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,tokenKey())
            val encrypted=cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
            prefs.edit().remove("token")
                .putString("token_iv",android.util.Base64.encodeToString(cipher.iv,android.util.Base64.NO_WRAP))
                .putString("token_cipher",android.util.Base64.encodeToString(encrypted,android.util.Base64.NO_WRAP))
                .apply()
        } catch(error:Exception) {
            runOnUiThread { toast("No se pudo proteger la sesión en este teléfono") }
        }
    }
    private fun readSecureToken():String {
        val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
        val encrypted=prefs.getString("token_cipher","")?:""
        if(encrypted.isNotBlank()) {
            return try {
                val iv=android.util.Base64.decode(prefs.getString("token_iv",""),android.util.Base64.NO_WRAP)
                val bytes=android.util.Base64.decode(encrypted,android.util.Base64.NO_WRAP)
                val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE,tokenKey(),javax.crypto.spec.GCMParameterSpec(128,iv))
                String(cipher.doFinal(bytes),Charsets.UTF_8)
            } catch(_:Exception) {
                prefs.edit().remove("token_iv").remove("token_cipher").apply()
                ""
            }
        }
        // Migrate an existing plaintext session token into Android Keystore encryption.
        val legacy=prefs.getString("token","")?:""
        if(legacy.isNotBlank())saveSecureToken(legacy)
        return legacy
    }
    private fun clearSecureToken() {
        getSharedPreferences("chatpro",MODE_PRIVATE).edit()
            .remove("token").remove("token_iv").remove("token_cipher").apply()
    }
    private fun showHome(tab:String) {
        backAction=null
        homeTab=tab
        homeSubPage=""
        registerPushToken()
        socket?.disconnect()
        connectCallSocket()
        // Navegación inferior moderna inspirada en mensajería: Chats, Contactos, Estados y Llamadas.
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(10,8,10,8)
        }
        val topBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(4,4,4,8)
        }
        val brand=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
        }
        brand.addView(TextView(this).apply {
            text="CHAT PRO";textSize=25f;setTextColor(Color.WHITE);typeface=android.graphics.Typeface.DEFAULT_BOLD;letterSpacing=0.02f
        })
        brand.addView(TextView(this).apply {
            text=me.optString("name","Usuario")+"  ·  @"+me.optString("username","")
            textSize=11f;setTextColor(accent)
        })
        topBar.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        val profile=TextView(this).apply {
            text="👤";textSize=22f;gravity=Gravity.CENTER
            setTextColor(Color.WHITE);setPadding(13,9,13,9)
            background=GradientDrawable().apply{setColor(Color.argb(255,42,57,66));cornerRadius=24f;setStroke(1,Color.argb(150,Color.red(accent),Color.green(accent),Color.blue(accent)))}
            contentDescription="Abrir perfil"
            setOnClickListener{showHome("Perfil")}
        }
        topBar.addView(profile,LinearLayout.LayoutParams(48,48))
        root.addView(topBar)
        header=TextView(this).apply {
            text="";textSize=12f;setTextColor(Color.rgb(134,150,156))
            setPadding(5,5,5,9)
        }
        root.addView(header)
        val mainScroll=ScrollView(this)
        content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(2,4,2,12)
        }
        mainScroll.addView(content)
        root.addView(mainScroll,LinearLayout.LayoutParams(-1,0,1f))
        val bottom=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            setPadding(5,8,5,5)
            background=GradientDrawable().apply {
                setColor(Color.rgb(32,44,51));cornerRadius=22f
                setStroke(1,Color.rgb(48,66,74))
            }
        }
        val tabs=listOf(
            Triple("💬","Chats","Chats"),
            Triple("👥","Contactos","Contactos"),
            Triple("🟣","Estados","Estados"),
            Triple("📞","Llamadas","Llamadas")
        )
        tabs.forEach { (symbol,label,destination) ->
            val selected=tab==destination
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER
                setPadding(3,7,3,7)
                background=GradientDrawable().apply {
                    setColor(if(selected)Color.argb(48,Color.red(accent),Color.green(accent),Color.blue(accent)) else Color.TRANSPARENT)
                    cornerRadius=16f
                }
                setOnClickListener{showHome(destination)}
            }
            item.addView(TextView(this).apply {
                text=symbol;textSize=19f;gravity=Gravity.CENTER
                setTextColor(if(selected)Color.WHITE else Color.LTGRAY)
            })
            item.addView(TextView(this).apply {
                text=label;textSize=10f;gravity=Gravity.CENTER
                setTextColor(if(selected)Color.WHITE else Color.LTGRAY)
                typeface=if(selected)android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            })
            bottom.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(bottom,LinearLayout.LayoutParams(-1,-2).apply{topMargin=7})
        setContentView(root)
        applyWallpaper("home_wallpaper")
        when(tab){
            "Chats"->loadConversations()
            "Contactos"->showContacts()
            "Estados"->showStatuses()
            "Llamadas"->showCalls()
            "Salas"->showPublicRooms()
            "Perfil"->showProfile()
        }
    }
    private fun registerPushToken() {
        if(token.isBlank()||FirebaseApp.getApps(this).isEmpty())return
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if(!task.isSuccessful)return@addOnCompleteListener
                val fcmToken=task.result?:return@addOnCompleteListener
                getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString("fcm_token",fcmToken).apply()
                api("POST","/api/push/devices",JSONObject().put("fcmToken",fcmToken),{},{error->
                    android.util.Log.w("CHATPRO-PUSH","No se pudo registrar el dispositivo: $error")
                })
            }
        } catch(error:Exception) {
            android.util.Log.w("CHATPRO-PUSH","Firebase Messaging no está configurado",error)
        }
    }
    private fun showCalls() {
        content.removeAllViews()
        header.text="Llamadas de voz y videollamadas · abre un chat privado para llamar"
        content.addView(note("Tus llamadas se realizan desde conversaciones privadas. Selecciona un contacto para abrir el chat y usar los botones de llamada o videollamada."))
        api("GET","/api/conversations",null,{data->
            val arr=data.optJSONArray("conversations")?:JSONArray()
            var found=false
            for(i in 0 until arr.length()){
                val c=arr.optJSONObject(i)?:continue
                if(c.optString("type")!="direct")continue
                val members=c.optJSONArray("members")?:JSONArray()
                val myId=me.optString("id")
                val peerId=(0 until members.length()).map{members.optString(it)}.firstOrNull{it.isNotBlank()&&it!=myId}?:continue
                found=true
                val title=c.optString("title").ifBlank{"Chat privado"}
                content.addView(action("📞  $title  ·  Abrir para llamar"){openConversation(c.optString("id"),title,peerId,"direct")})
            }
            if(!found)content.addView(note("Todavía no tienes conversaciones privadas. Entra a Contactos para buscar personas."))
        },{header.text=it})
    }
    private fun loadConversations() {
        header.text="Tus conversaciones privadas y grupos"
        content.removeAllViews()

        val search=field("Buscar chats o mensajes")
        search.setSingleLine(true)
        content.addView(search)
        val list=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(0,4,0,8)
        }
        content.addView(list)

        api("GET","/api/conversations",null,{data->
            val arr=data.optJSONArray("conversations")?:JSONArray()
            fun render(query:String) {
                list.removeAllViews()
                val needle=query.trim().lowercase()
                var shown=0
                for(i in 0 until arr.length()){
                    val c=arr.optJSONObject(i)?:continue
                    val id=c.optString("id")
                    val title=c.optString("title").ifBlank{if(c.optString("type")=="group")"Grupo" else if(c.optString("type")=="room")"Sala" else "Chat privado"}
                    val type=c.optString("type")
                    val last=c.optJSONObject("lastMessage")
                    val preview=when {
                        last==null -> "Toca para comenzar a conversar"
                        last.optString("text").isNotBlank() -> last.optString("text")
                        last.optJSONObject("media")!=null -> "📎 Archivo multimedia"
                        else -> "Mensaje"
                    }
                    if(needle.isNotBlank()&&!title.lowercase().contains(needle)&&!preview.lowercase().contains(needle))continue
                    shown++
                    val unread=c.optInt("unread",0)
                    val members=c.optJSONArray("members")?:JSONArray()
                    val myId=me.optString("id")
                    val peerId=(0 until members.length()).map{members.optString(it)}.firstOrNull{it.isNotBlank()&&it!=myId}?: ""
                    val row=LinearLayout(this).apply {
                        orientation=LinearLayout.HORIZONTAL
                        gravity=Gravity.CENTER_VERTICAL
                        setPadding(12,12,12,12)
                        background=GradientDrawable().apply {
                            setColor(Color.argb(245,32,44,51))
                            cornerRadius=18f
                            setStroke(1,Color.argb(65,150,150,180))
                        }
                        setOnClickListener{openConversation(id,title,peerId,type)}
                    }
                    val avatar=TextView(this).apply {
                        text=title.trim().take(1).uppercase().ifBlank{"C"}
                        textSize=20f
                        gravity=Gravity.CENTER
                        setTextColor(Color.WHITE)
                        typeface=android.graphics.Typeface.DEFAULT_BOLD
                        background=GradientDrawable().apply {
                            setColor(Color.argb(190,Color.red(accent),Color.green(accent),Color.blue(accent)))
                            shape=GradientDrawable.OVAL
                        }
                    }
                    row.addView(avatar,LinearLayout.LayoutParams(48,48))
                    val details=LinearLayout(this).apply {
                        orientation=LinearLayout.VERTICAL
                        setPadding(12,0,8,0)
                    }
                    val titleView=TextView(this).apply {
                        text=title
                        textSize=15f
                        setTextColor(Color.WHITE)
                        typeface=android.graphics.Typeface.DEFAULT_BOLD
                        maxLines=1
                    }
                    val previewView=TextView(this).apply {
                        text=preview
                        textSize=12f
                        setTextColor(Color.LTGRAY)
                        maxLines=1
                    }
                    details.addView(titleView)
                    details.addView(previewView)
                    row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                    val time=last?.optString("createdAt","")?: ""
                    if(time.length>=16){
                        val clock=TextView(this).apply {
                            text=time.substring(11,16)
                            textSize=10f
                            gravity=Gravity.CENTER
                            setTextColor(Color.LTGRAY)
                            setPadding(4,0,4,0)
                        }
                        row.addView(clock)
                    }
                    if(unread>0){
                        val badge=TextView(this).apply {
                            text=if(unread>99)"99+" else unread.toString()
                            textSize=11f
                            gravity=Gravity.CENTER
                            setTextColor(Color.WHITE)
                            setPadding(8,5,8,5)
                            background=GradientDrawable().apply {
                                setColor(accent)
                                cornerRadius=30f
                            }
                        }
                        row.addView(badge)
                    }
                    list.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=7})
                }
                if(shown==0)list.addView(note(if(needle.isBlank())"Aún no tienes chats. Entra a Contactos para buscar personas." else "No hay conversaciones que coincidan con «$query»."))
            }
            render("")
            search.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { render(s?.toString()?: "") }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
        },{header.text=it})
    }
    private fun createRoomDialog(visibility:String) {
        val title=field("Nombre de la sala")
        val description=field("Descripción (opcional)")
        val users=field("Usuarios iniciales separados por coma (opcional)")
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,4,16,4);addView(title);addView(description);addView(users)}
        AlertDialog.Builder(this).setTitle(if(visibility=="public")"Crear sala pública" else "Crear sala privada").setMessage(if(visibility=="public")"Cualquier usuario podrá encontrarla y unirse." else "Solo tú y las personas invitadas podrán verla.").setView(box)
            .setNegativeButton("Cancelar",null).setPositiveButton("Crear"){_,_->
                if(title.text.toString().trim().length<3){toast("Pon un nombre de al menos 3 caracteres");return@setPositiveButton}
                val names=users.text.toString().split(",").map{it.trim().removePrefix("@")}.filter{it.matches(Regex("[a-zA-Z0-9_.-]{3,24}"))}.distinct()
                if(visibility=="private"&&names.isEmpty()){toast("Agrega al menos un usuario para invitar a la sala privada");return@setPositiveButton}
                val body=JSONObject().put("type","room").put("visibility",visibility).put("title",title.text.toString()).put("description",description.text.toString()).put("usernames",JSONArray(names))
                api("POST","/api/conversations",body,{data->val room=data.optJSONObject("conversation")?:return@api;openConversation(room.optString("id"),room.optString("title","Sala"),"","room")},{header.text=it})
            }.show()
    }
    private fun showPublicRooms() {
        homeSubPage="Salas"
        content.removeAllViews();header.text="Salas públicas · puedes unirte o abrir las que ya frecuentas"
        content.addView(action("＋ Crear sala pública"){createRoomDialog("public")})
        api("GET","/api/rooms",null,{data->
            val rooms=data.optJSONArray("rooms")?:JSONArray()
            if(rooms.length()==0)content.addView(note("Todavía no hay salas públicas. ¡Crea la primera!"))
            for(i in 0 until rooms.length()){
                val room=rooms.optJSONObject(i)?:continue
                val id=room.optString("id");val title=room.optString("title","Sala")
                val members=room.optInt("members")
                val joined=room.optBoolean("isMember")
                content.addView(action("${if(joined)"Abrir" else "Unirse"} · $title · $members miembros"){
                    if(joined)openConversation(id,title,"","room")
                    else api("POST","/api/rooms/$id/join",JSONObject(),{openConversation(id,title,"","room")},{toast(it)})
                })
                if(room.optString("description").isNotBlank())content.addView(note(room.optString("description")))
            }
        },{header.text=it})
    }
    private fun showContacts() {
        content.removeAllViews();header.text="Busca usuarios para iniciar una conversación"
        content.addView(action("＋ Crear grupo"){createGroupDialog()})
        content.addView(action("＋ Crear sala pública"){createRoomDialog("public")})
        content.addView(action("＋ Crear sala privada"){createRoomDialog("private")})
        content.addView(action("Explorar salas públicas"){showPublicRooms()})
        val q=field("Buscar por nombre o usuario");content.addView(q)
        val result=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};content.addView(result)
        content.addView(action("Buscar"){searchUsers(q.text.toString(),result)})
        q.setOnEditorActionListener{_,_,_->searchUsers(q.text.toString(),result);true}
        searchUsers("",result)
    }
    private fun createGroupDialog() {
        val title=field("Nombre del grupo")
        val users=field("Usuarios separados por coma")
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,4,16,4);addView(title);addView(users)}
        AlertDialog.Builder(this).setTitle("Nuevo grupo").setMessage("Escribe los nombres de usuario de las personas que quieres agregar.").setView(box)
            .setNegativeButton("Cancelar",null).setPositiveButton("Crear"){_,_->
                val names=users.text.toString().split(",").map{it.trim().removePrefix("@")}.filter{it.matches(Regex("[a-zA-Z0-9_.-]{3,24}"))}.distinct()
                if(names.isEmpty()){toast("Agrega al menos otro usuario");return@setPositiveButton}
                val body=JSONObject().put("type","group").put("title",title.text.toString()).put("usernames",JSONArray(names))
                api("POST","/api/conversations",body,{data->val conv=data.optJSONObject("conversation")?:return@api;openConversation(conv.optString("id"),conv.optString("title","Grupo"),"","group")},{header.text=it})
            }.show()
    }
    private fun searchUsers(term:String,target:LinearLayout?=null) {
        val box=target?:content
        if(term.length<2){if(target!=null)box.removeAllViews();return}
        api("GET","/api/users/search?q="+Uri.encode(term),null,{data->
            box.removeAllViews()
            val arr=data.optJSONArray("users")?:JSONArray()
            for(i in 0 until arr.length()){
                val u=arr.optJSONObject(i)?:continue
                box.addView(action("${u.optString("name")}  ·  @${u.optString("username")}"){createConversation(u.optString("id"),u.optString("name"))})
            }
            if(arr.length()==0)box.addView(note("No se encontraron usuarios."))
        },{header.text=it})
    }
    private fun createConversation(userId:String,name:String,shareMedia:Boolean=false) {
        api("POST","/api/conversations",JSONObject().put("members",JSONArray().put(userId)),{data->
            val c=data.optJSONObject("conversation")?:return@api
            openConversation(c.optString("id"),name,userId,"direct")
            if(shareMedia) android.os.Handler(mainLooper).postDelayed({chooseShareMedia()},900)
        },{header.text=it})
    }
    private fun openConversation(id:String,title:String,peerId:String="",conversationType:String="direct") {
        currentConversation=id;currentTitle=title;currentPeerId=if(conversationType=="direct")peerId else "";currentConversationType=conversationType
        reactionViews.clear()
        val returnTab=homeTab
        val returnSubPage=homeSubPage
        backAction={
            showHome(returnTab)
            if(returnSubPage=="Salas") showPublicRooms()
        }
        frame(title,"Conversación protegida · CHAT PRO")
        header.text="Cargando historial…"
        val menuRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END}
        menuRow.addView(action("⋮  Opciones"){showConversationOptions(id,conversationType)},LinearLayout.LayoutParams(-2,44))
        root.addView(menuRow,root.indexOfChild(header)+1)
        composer=field("Escribe un mensaje")
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        row.addView(composer,LinearLayout.LayoutParams(0,52,1f))
        row.addView(action("🎙"){toggleVoiceRecording()},LinearLayout.LayoutParams(48,52))
        row.addView(action("📎"){uploadMode="chat";chooseFile()},LinearLayout.LayoutParams(48,52))
        row.addView(action("➤"){sendMessage()},LinearLayout.LayoutParams(52,52))
        root.addView(row)
        applyWallpaper("chat_wallpaper_"+id)
        api("GET","/api/conversations/$id/messages",null,{data->
            val arr=data.optJSONArray("messages")?:JSONArray()
            val messages=(0 until arr.length()).mapNotNull{arr.optJSONObject(it)}
            showConversationHistory(id,messages,messages.size>=500)
            api("POST","/api/conversations/$id/read",JSONObject(),{},{})
            header.text="Conectando al chat en tiempo real…";connectConversation()
        },{header.text=it})
    }
    private fun showConversationOptions(id:String,conversationType:String) {
        val options=mutableListOf<String>()
        val actions=mutableListOf<()->Unit>()
        if(conversationType=="direct"&&currentPeerId.isNotBlank()) {
            options.add("📞 Llamada de voz");actions.add{startCall("audio")}
            options.add("🎥 Videollamada");actions.add{startCall("video")}
        }
        options.add("🔎 Buscar dentro de esta conversación");actions.add{searchConversationDialog(id)}
        options.add("🎨 Cambiar fondo de este chat");actions.add{chooseWallpaper("chat_wallpaper_"+id)}
        if(conversationType=="group"||conversationType=="room") {
            options.add("👥 Administrar grupo y miembros");actions.add{manageConversationMembers(id)}
            options.add("＋ Agregar integrante");actions.add{addConversationMemberDialog(id)}
        }
        options.add("⭐ Ver favoritos y mensajes fijados");actions.add{showSavedMessages()}
        options.add("🧹 Limpiar historial");actions.add{confirmClearConversationHistory(id)}
        AlertDialog.Builder(this).setTitle("Opciones de conversación")
            .setItems(options.toTypedArray()){_,which->actions[which].invoke()}.show()
    }
    private fun showConversationHistory(conversationId:String,messages:List<JSONObject>,canLoadOlder:Boolean) {
        content.removeAllViews()
        if(canLoadOlder&&messages.isNotEmpty()) {
            content.addView(action("⬆ Cargar mensajes anteriores") {
                val cursor=messages.first().optString("createdAt")
                api("GET","/api/conversations/$conversationId/messages?before="+Uri.encode(cursor)+"&limit=500",null,{data->
                    val arr=data.optJSONArray("messages")?:JSONArray()
                    val older=(0 until arr.length()).mapNotNull{arr.optJSONObject(it)}
                    if(older.isEmpty()) {
                        toast("Ya llegaste al inicio de la conversación")
                        showConversationHistory(conversationId,messages,false)
                    } else {
                        showConversationHistory(conversationId,older+messages,older.size>=500)
                    }
                },{toast("No se pudieron cargar mensajes anteriores: $it")})
            })
        }
        messages.forEach{renderConversationMessage(it)}
    }
    private fun showSavedMessages() {
        api("GET","/api/bookmarks",null,{data->
            val arr=data.optJSONArray("bookmarks")?:JSONArray()
            if(arr.length()==0){toast("Todavía no tienes mensajes guardados");return@api}
            val labels=(0 until arr.length()).map{idx->
                val row=arr.optJSONObject(idx)?:JSONObject()
                val msg=row.optJSONObject("messages")?:JSONObject()
                val pin=if(row.optBoolean("is_pinned"))"📌 " else "⭐ "
                pin+msg.optString("body","Mensaje").take(85)
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Tus mensajes guardados").setItems(labels){_,which->
                val row=arr.optJSONObject(which)?:return@setItems
                val msg=row.optJSONObject("messages")?:JSONObject()
                AlertDialog.Builder(this).setMessage(msg.optString("body","Archivo multimedia"))
                    .setPositiveButton("Quitar de guardados"){_,_->api("DELETE","/api/messages/${msg.optString("id")}/bookmark",null,{toast("Eliminado de guardados")},{toast(it)})}
                    .setNegativeButton("Cerrar",null).show()
            }.show()
        },{toast(it)})
    }
    private fun searchConversationDialog(conversationId:String) {
        val query=field("Escribe una palabra o frase")
        AlertDialog.Builder(this).setTitle("Buscar en mensajes").setView(query)
            .setNegativeButton("Cerrar",null).setPositiveButton("Buscar"){_,_->
                val term=query.text.toString().trim()
                if(term.length<2){toast("Escribe al menos 2 caracteres");return@setPositiveButton}
                api("GET","/api/conversations/$conversationId/search?q="+Uri.encode(term),null,{data->
                    val arr=data.optJSONArray("messages")?:JSONArray()
                    if(arr.length()==0){toast("No encontré mensajes");return@api}
                    val labels=(0 until arr.length()).map{idx->
                        val m=arr.optJSONObject(idx)?:JSONObject()
                        val author=m.optJSONObject("user")?.optString("name","Usuario")?: "Usuario"
                        "$author · "+m.optString("text").take(100)
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Resultados: ${arr.length()}")
                        .setItems(labels){_,idx->
                            val m=arr.optJSONObject(idx)?:return@setItems
                            val date=m.optString("createdAt").replace("T"," ").take(16)
                            AlertDialog.Builder(this).setMessage(m.optString("text")+"\n\n"+date)
                                .setPositiveButton("Entendido",null).show()
                        }.show()
                },{toast("No se pudo buscar: $it")})
            }.show()
    }
    private fun forwardMessageDialog(messageId:String) {
        api("GET","/api/conversations",null,{data->
            val arr=data.optJSONArray("conversations")?:JSONArray()
            val targets=mutableListOf<JSONObject>();val labels=mutableListOf<String>()
            for(i in 0 until arr.length()){
                val c=arr.optJSONObject(i)?:continue
                if(c.optString("id")==currentConversation)continue
                targets.add(c);labels.add(c.optString("title").ifBlank{"Conversación"})
            }
            if(targets.isEmpty()){toast("No tienes otro chat para reenviar");return@api}
            AlertDialog.Builder(this).setTitle("Reenviar a…").setItems(labels.toTypedArray()){_,which->
                val target=targets[which]
                api("POST","/api/messages/$messageId/forward",JSONObject().put("conversationId",target.optString("id")),{toast("Mensaje reenviado")},{toast("No se pudo reenviar: $it")})
            }.show()
        },{toast(it)})
    }
    private fun confirmClearConversationHistory(conversationId:String) {
        AlertDialog.Builder(this)
            .setTitle("¿Limpiar historial?")
            .setMessage("Se ocultarán todos los mensajes de esta conversación únicamente en tu cuenta. Los demás participantes conservarán sus mensajes y archivos.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Limpiar"){_,_->
                api("POST","/api/conversations/$conversationId/clear-history",JSONObject(),{data->
                    toast("Historial limpiado")
                    refreshConversationMessages()
                },{toast("No se pudo limpiar el historial: $it")})
            }.show()
    }
    private fun manageConversationMembers(conversationId:String) {
        api("GET","/api/conversations/$conversationId/members",null,{data->
            val arr=data.optJSONArray("members")?:JSONArray()
            if(arr.length()==0){toast("No hay integrantes para mostrar");return@api}
            val labels=(0 until arr.length()).map{index->
                val row=arr.optJSONObject(index)?:JSONObject()
                val user=row.optJSONObject("user")?:JSONObject()
                val role=row.optString("role","member")
                val suffix=when(role){"owner"->" · Propietario";"admin"->" · Administrador";"moderator"->" · Moderador";else->" · Miembro"}
                user.optString("name","Usuario")+suffix
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Integrantes del grupo").setItems(labels){_,which->
                val row=arr.optJSONObject(which)?:return@setItems
                val user=row.optJSONObject("user")?:return@setItems
                val userId=user.optString("id")
                val role=row.optString("role","member")
                val isSelf=userId==me.optString("id")
                val canManage=data.optBoolean("canManage")
                val isOwner=data.optBoolean("isOwner")
                if(isSelf) {
                    val actions=if(role=="owner") arrayOf("Cerrar") else arrayOf("Salir del grupo","Cerrar")
                    AlertDialog.Builder(this).setTitle("Tu participación").setItems(actions){_,choice->
                        if(choice==0&&role!="owner") {
                            api("DELETE","/api/conversations/$conversationId/members/$userId",null,{toast("Saliste del grupo");showHome("Chats")},{toast(it)})
                        }
                    }.show()
                } else if(canManage) {
                    val actions=if(isOwner) arrayOf("Transferir propiedad","Hacer administrador","Hacer moderador","Dejar como miembro","Quitar integrante","Cerrar")
                        else arrayOf("Quitar integrante","Cerrar")
                    AlertDialog.Builder(this).setTitle(user.optString("name","Integrante")).setItems(actions){_,choice->
                        if(isOwner&&choice==0) {
                            AlertDialog.Builder(this).setMessage("¿Transferir la propiedad del grupo a ${user.optString("name","este integrante")}? Tu cuenta pasará a ser administradora.")
                                .setNegativeButton("Cancelar",null)
                                .setPositiveButton("Transferir"){_,_->api("PATCH","/api/conversations/$conversationId/owner",JSONObject().put("userId",userId),{toast("Propiedad transferida")},{toast(it)})}.show()
                        } else if(isOwner&&choice in 1..3) {
                            val nextRole=when(choice){1->"admin";2->"moderator";else->"member"}
                            api("PATCH","/api/conversations/$conversationId/members/$userId/role",JSONObject().put("role",nextRole),{toast("Rol actualizado")},{toast(it)})
                        } else if((isOwner&&choice==4)||(!isOwner&&choice==0)) {
                            AlertDialog.Builder(this).setMessage("¿Quitar a ${user.optString("name","este integrante")} del grupo?")
                                .setNegativeButton("Cancelar",null)
                                .setPositiveButton("Quitar"){_,_->api("DELETE","/api/conversations/$conversationId/members/$userId",null,{toast("Integrante retirado");manageConversationMembers(conversationId)},{toast(it)})}.show()
                        }
                    }.show()
                }
            }.setNegativeButton("Cerrar",null).show()
        },{toast("No se pudo cargar el grupo: $it")})
    }
    private fun addConversationMemberDialog(conversationId:String) {
        val username=field("Nombre de usuario a invitar")
        AlertDialog.Builder(this).setTitle("Agregar integrante").setMessage("Los propietarios, administradores y moderadores autorizados pueden invitar integrantes.").setView(username)
            .setNegativeButton("Cancelar",null).setPositiveButton("Buscar e invitar"){_,_->
                val term=username.text.toString().trim().removePrefix("@").lowercase()
                if(!term.matches(Regex("[a-z0-9_.-]{3,24}"))){toast("Escribe un usuario válido");return@setPositiveButton}
                api("GET","/api/users/search?q="+Uri.encode(term),null,{data->
                    val users=data.optJSONArray("users")?:JSONArray()
                    var found:JSONObject?=null
                    for(i in 0 until users.length()){val u=users.optJSONObject(i)?:continue;if(u.optString("username").equals(term,true)){found=u;break}}
                    val user=found
                    if(user==null){toast("No se encontró @"+term);return@api}
                    api("POST","/api/conversations/$conversationId/members",JSONObject().put("userId",user.optString("id")),{toast("@$term fue agregado")},{toast(it)})
                },{toast(it)})
            }.show()
    }
    private fun connectCallSocket() {
        try {
            if(callSocket?.connected()==true)return
            callSocket?.disconnect();callSocket?.off()
            callSocket=IO.socket(baseUrl,IO.Options.builder().setAuth(mapOf("token" to token)).setReconnection(true).setTimeout(30000).build())
            callSocket?.on("chat:notification"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                runOnUiThread { showMessageNotification(obj) }
            }
            callSocket?.on("call:incoming"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                runOnUiThread{showIncomingCall(obj)}
            }
            callSocket?.on(Socket.EVENT_CONNECT_ERROR){args->
                android.util.Log.w("CHATPRO-CALL","No se pudo conectar el canal de llamadas: "+(args.firstOrNull()?.toString()?: ""))
            }
            callSocket?.connect()
        }catch(error:Exception){android.util.Log.e("CHATPRO-CALL","No se pudo preparar el canal de llamadas",error)}
    }
    private fun connectConversation() {
        val idForSocket=currentConversation
        reactionViews.clear()
        try {
            socket=IO.socket(baseUrl,IO.Options.builder().setAuth(mapOf("token" to token)).setReconnection(true).setTimeout(30000).build())
            socket?.on(Socket.EVENT_CONNECT){runOnUiThread{header.text="En línea · $currentTitle"}}
            socket?.on("conversation:history"){args->
                val a=args.firstOrNull() as? JSONArray?:return@on
                runOnUiThread{reactionViews.clear();content.removeAllViews();for(i in 0 until a.length())renderConversationMessage(a.optJSONObject(i)?:continue)}
            }
            socket?.on("conversation:message"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                runOnUiThread{renderConversationMessage(obj)}
            }
            socket?.on("message:reaction"){args->
                val event=args.firstOrNull() as? JSONObject?:return@on
                val messageId=event.optString("messageId")
                val reactions=event.optJSONArray("reactions")?:JSONArray()
                runOnUiThread{if(currentConversation==idForSocket)updateMessageReactions(messageId,reactions)}
            }
            socket?.on("message:edited"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("message:deleted"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("message:hidden"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("conversation:read"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("conversation:delivered"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("presence:joined"){runOnUiThread{header.text="En línea · $currentTitle"}}
            socket?.on("presence:left"){runOnUiThread{header.text="Miembro desconectado · $currentTitle"}}
            socket?.on("call:started"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                runOnUiThread{launchCallActivity(obj.optString("callId"),obj.optString("mediaType"),true,currentTitle,obj.optString("conversationId",currentConversation))}
            }
            socket?.on("call:error"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                runOnUiThread{toast(obj.optString("message","No se pudo iniciar la llamada"))}
            }
            socket?.on("call:unavailable"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                runOnUiThread{toast(obj.optString("message","La otra persona no está conectada"))}
            }
            socket?.on("call:ended"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                runOnUiThread{if(obj.optString("reason")=="no-answer")toast("La persona no respondió")}
            }
            socket?.on("call:rejected"){runOnUiThread{toast("La llamada fue rechazada")}}
            socket?.once(Socket.EVENT_CONNECT){socket?.emit("conversation:join",JSONObject().put("conversationId",currentConversation))}
            socket?.connect()
        }catch(e:Exception){header.text="Chat en línea no disponible: ${e.message}"}
    }
    private fun startCall(mediaType:String) {
        val s=socket
        if(currentPeerId.isBlank()){toast("Las llamadas solo están disponibles en chats privados individuales");return}
        if(s==null||!s.connected()){toast("Espera a que el chat esté conectado para llamar");return}
        s.emit("call:start",JSONObject().put("conversationId",currentConversation).put("toUserId",currentPeerId).put("mediaType",mediaType))
        toast(if(mediaType=="video")"Solicitando videollamada…" else "Llamando…")
    }
    private fun setupMessageNotifications() {
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) {
            val manager=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel=NotificationChannel(notificationChannelId,"Mensajes de CHAT PRO",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Avisos de mensajes privados y salas"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),92)
        }
    }
    private fun showMessageNotification(data:JSONObject) {
        val conversationId=data.optString("conversationId")
        if(conversationId.isNotBlank() && conversationId==currentConversation)return
        val sender=data.optString("senderName","Nuevo mensaje")
        val body=data.optString("preview","Te envió un mensaje")
        val intent=Intent(this,MainActivity::class.java).apply { flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=23)PendingIntent.FLAG_IMMUTABLE else 0)
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,notificationChannelId) else Notification.Builder(this)
        val notification=builder.setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(sender).setContentText(body).setAutoCancel(true).setContentIntent(pending).build()
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify((conversationId+sender).hashCode(),notification) } catch(_:Exception) {}
    }
    private fun hashPin(value:String,salt:String):String {
        val saltBytes=android.util.Base64.decode(salt,android.util.Base64.NO_WRAP)
        val spec=javax.crypto.spec.PBEKeySpec(value.toCharArray(),saltBytes,150000,256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
        } finally { spec.clearPassword() }
    }
    private fun verifyPin(value:String):Boolean {
        val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
        val now=System.currentTimeMillis()
        if(prefs.getLong("pin_lock_until",0L)>now)return false
        val saved=prefs.getString("app_pin","")?:""
        val salt=prefs.getString("app_pin_salt","")?:""
        val valid=if(salt.isNotBlank()) {
            try { hashPin(value,salt)==saved } catch(_:Exception) { false }
        } else {
            val legacy=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
            if(saved==value||saved==legacy) {
                val newSalt=ByteArray(16).also{java.security.SecureRandom().nextBytes(it)}
                val encoded=android.util.Base64.encodeToString(newSalt,android.util.Base64.NO_WRAP)
                prefs.edit().putString("app_pin_salt",encoded).putString("app_pin",hashPin(value,encoded)).apply()
                true
            } else false
        }
        if(valid) prefs.edit().remove("pin_failures").remove("pin_lock_until").apply()
        else {
            val failures=prefs.getInt("pin_failures",0)+1
            val edit=prefs.edit().putInt("pin_failures",failures)
            if(failures>=5)edit.putLong("pin_lock_until",now+30000L).putInt("pin_failures",0)
            edit.apply()
        }
        return valid
    }
    private fun showSecurityGate(onUnlock:()->Unit) {
        val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
        val savedPin=prefs.getString("app_pin","")?:""
        if(savedPin.isBlank()){onUnlock();return}
        if(!prefs.getBoolean("biometric_enabled",false)){showPinGate(onUnlock);return}
        val manager=BiometricManager.from(this)
        if(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)!=BiometricManager.BIOMETRIC_SUCCESS) {
            showPinGate(onUnlock);return
        }
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) { onUnlock() }
            override fun onAuthenticationFailed() { toast("Huella no reconocida. Inténtalo otra vez.") }
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence) { showPinGate(onUnlock) }
        })
        val info=BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear CHAT PRO")
            .setSubtitle("Usa tu huella digital o el PIN de respaldo")
            .setNegativeButtonText("Usar PIN")
            .build()
        prompt.authenticate(info)
    }
    private fun showPinGate(onUnlock:()->Unit) {
        val pin=EditText(this).apply { hint="PIN de acceso";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("CHAT PRO bloqueado").setMessage("Introduce tu PIN para continuar.").setView(pin)
            .setCancelable(false).setPositiveButton("Desbloquear"){_,_->
                val saved=getSharedPreferences("chatpro",MODE_PRIVATE).getString("app_pin","")?:""
                if(verifyPin(pin.text.toString()))onUnlock() else { toast(if(getSharedPreferences("chatpro",MODE_PRIVATE).getLong("pin_lock_until",0L)>System.currentTimeMillis())"Demasiados intentos. Espera 30 segundos." else "PIN incorrecto");showPinGate(onUnlock) }
            }.show()
    }
    private fun showPinSettings() {
        val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
        val current=prefs.getString("app_pin","")?:""
        if(current.isNotBlank()) {
            AlertDialog.Builder(this).setTitle("Bloqueo con PIN").setMessage("El bloqueo está activado.")
                .setItems(arrayOf("Cambiar PIN","Desactivar bloqueo")){_,which->
                    if(which==0) promptNewPin() else AlertDialog.Builder(this).setTitle("Desactivar bloqueo").setMessage("¿Quitar el PIN de este teléfono?")
                        .setNegativeButton("Cancelar",null).setPositiveButton("Desactivar"){_,_->prefs.edit().remove("app_pin").remove("app_pin_salt").remove("pin_failures").remove("pin_lock_until").remove("biometric_enabled").apply();toast("Bloqueo desactivado")}.show()
                }.show()
        } else promptNewPin()
    }
    private fun showBiometricSettings() {
        val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
        if((prefs.getString("app_pin","")?:"").isBlank()) {
            AlertDialog.Builder(this).setTitle("Primero configura un PIN")
                .setMessage("El PIN será tu método alternativo si la huella no está disponible.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Configurar PIN"){_,_->promptNewPin()}
                .show()
            return
        }
        val manager=BiometricManager.from(this)
        if(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)!=BiometricManager.BIOMETRIC_SUCCESS) {
            toast("Configura una huella digital o biometría compatible en los ajustes de Android.")
            return
        }
        val enabled=prefs.getBoolean("biometric_enabled",false)
        AlertDialog.Builder(this).setTitle("Desbloqueo con huella digital")
            .setMessage(if(enabled)"La huella está activada. ¿Quieres desactivarla?" else "Activa la huella digital. Tu PIN seguirá disponible como respaldo.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton(if(enabled)"Desactivar" else "Activar"){_,_->
                prefs.edit().putBoolean("biometric_enabled",!enabled).apply()
                toast(if(enabled)"Huella desactivada" else "Huella activada para desbloquear CHAT PRO")
            }.show()
    }
    private fun promptNewPin() {
        val pin=EditText(this).apply { hint="Nuevo PIN (4 a 8 dígitos)";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val confirm=EditText(this).apply { hint="Confirmar PIN";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(20,4,20,4);addView(pin);addView(confirm) }
        AlertDialog.Builder(this).setTitle("Activar bloqueo de CHAT PRO").setMessage("El PIN se guarda localmente en este teléfono.")
            .setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                val p=pin.text.toString()
                if(!p.matches(Regex("[0-9]{4,8}"))||p!=confirm.text.toString()) { toast("El PIN debe tener 4 a 8 dígitos y coincidir");return@setPositiveButton }
                val saltBytes=ByteArray(16).also{java.security.SecureRandom().nextBytes(it)}
                val salt=android.util.Base64.encodeToString(saltBytes,android.util.Base64.NO_WRAP)
                getSharedPreferences("chatpro",MODE_PRIVATE).edit()
                    .putString("app_pin_salt",salt).putString("app_pin",hashPin(p,salt))
                    .remove("pin_failures").remove("pin_lock_until").apply()
                toast("Bloqueo con PIN activado. Se solicitará al abrir CHAT PRO.")
            }.show()
    }
    private fun toggleVoiceRecording() {
        if(isRecordingVoice) { finishVoiceRecording();return }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),91);return
        }
        beginVoiceRecording()
    }
    private fun beginVoiceRecording() {
        try {
            val file=File(cacheDir,"chatpro_voice_"+System.currentTimeMillis()+".m4a")
            @Suppress("DEPRECATION")
            val recorder=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this) else MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare();recorder.start()
            voiceRecorder=recorder;voiceFile=file;isRecordingVoice=true
            header.text="🔴 Grabando nota de voz… toca 🎙 Voz para terminar y enviar."
            toast("Grabando voz. Toca Voz otra vez para enviar.")
        } catch(e:Exception) { voiceRecorder?.release();voiceRecorder=null;isRecordingVoice=false;toast("No se pudo iniciar la grabación: ${e.message}") }
    }
    private fun finishVoiceRecording() {
        val recorder=voiceRecorder;val file=voiceFile
        isRecordingVoice=false;voiceRecorder=null;voiceFile=null
        try { recorder?.stop();recorder?.release() } catch(_:Exception) { recorder?.release();file?.delete();toast("La grabación fue demasiado corta");return }
        if(file==null||!file.exists()||file.length()<500) { file?.delete();toast("No se grabó audio suficiente");return }
        uploadVoiceMessage(file)
    }
    private fun uploadVoiceMessage(file:File) {
        header.text="Subiendo nota de voz…"
        Thread {
            try {
                val body=MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file",file.name,file.asRequestBody("audio/mp4".toMediaTypeOrNull())).build()
                val req=Request.Builder().url("$baseUrl/api/uploads").header("Authorization","Bearer $token").post(body).build()
                http.newCall(req).execute().use { res->
                    val data=JSONObject(res.body?.string()?: "{}")
                    if(!res.isSuccessful)throw Exception(data.optString("error","No se pudo subir la voz"))
                    val media=JSONObject().put("name","Nota de voz").put("mimeType","audio/mp4").put("size",data.optLong("size")).put("downloadPath",data.optString("downloadPath"))
                    runOnUiThread {
                        val s=socket
                        if(s==null||!s.connected())toast("La voz se subió, pero el chat no está conectado")
                        else { s.emit("conversation:send",JSONObject().put("text","").put("media",media));header.text="Nota de voz enviada" }
                    }
                }
            } catch(e:Exception) { runOnUiThread{toast("Error al enviar voz: ${e.message}")} }
            finally { file.delete() }
        }.start()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==91 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)beginVoiceRecording()
        if(requestCode==91 && grantResults.firstOrNull()!=PackageManager.PERMISSION_GRANTED)toast("Necesitas permitir el micrófono para grabar notas de voz")
    }
    private fun showRoomUserActions(userId:String,name:String) {
        if(userId.isBlank()||userId==me.optString("id"))return
        AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("Iniciar chat privado","Compartir audio o imagen","Bloquear usuario","Reportar usuario")){_,which->
            when(which){
                0->createConversation(userId,name,false)
                1->createConversation(userId,name,true)
                2->AlertDialog.Builder(this).setMessage("No recibirás ni podrás iniciar conversaciones con esta persona.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Bloquear"){_,_->api("POST","/api/users/$userId/block",JSONObject(),{toast("Usuario bloqueado")},{toast(it)})}.show()
                3->{
                    val reason=field("Describe el motivo del reporte")
                    AlertDialog.Builder(this).setTitle("Reportar usuario").setView(reason)
                        .setNegativeButton("Cancelar",null).setPositiveButton("Enviar reporte"){_,_->api("POST","/api/users/$userId/report",JSONObject().put("reason",reason.text.toString()),{toast("Reporte enviado")},{toast(it)})}.show()
                }
            }
        }.show()
    }
    private fun chooseShareMedia() {
        val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/*","audio/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(pick,42) } catch(_:Exception) { toast("No se pudo abrir Archivos") }
    }

    private fun showIncomingCall(call:JSONObject) {
        val callId=call.optString("callId")
        val mediaType=call.optString("mediaType","audio")
        val from=call.optJSONObject("fromUser")?:JSONObject()
        val name=from.optString("name","Tu contacto")
        AlertDialog.Builder(this)
            .setTitle(if(mediaType=="video")"Videollamada entrante" else "Llamada entrante")
            .setMessage(name+" te está llamando")
            .setNegativeButton("Rechazar"){_,_->callSocket?.emit("call:reject",JSONObject().put("callId",callId))}
            .setPositiveButton("Aceptar"){_,_->launchCallActivity(callId,mediaType,false,name,call.optString("conversationId",currentConversation))}
            .setOnCancelListener{callSocket?.emit("call:reject",JSONObject().put("callId",callId))}
            .show()
    }
    private fun launchCallActivity(callId:String,mediaType:String,isCaller:Boolean,peerName:String,conversationId:String=currentConversation) {
        val intent=Intent(this,CallActivity::class.java).apply{
            putExtra("server_url",baseUrl);putExtra("token",token)
            putExtra("conversation_id",conversationId);putExtra("call_id",callId)
            putExtra("media_type",mediaType);putExtra("is_caller",isCaller)
            putExtra("peer_name",peerName)
        }
        startActivity(intent)
    }
    private fun sendMessage() {
        val text=composer.text.toString().trim()
        if(text.isBlank())return
        val s=socket
        if(s==null||!s.connected()){toast("Espera a que el chat conecte");return}
        s.emit("conversation:send",JSONObject().put("text",text));composer.setText("")
    }
    private fun showMessageActions(obj:JSONObject,mine:Boolean) {
        val messageId=obj.optString("id")
        val user=obj.optJSONObject("user")?:JSONObject()
        val author=user.optString("name","Usuario")
        val authorId=user.optString("id")
        val options=mutableListOf("🗑 Eliminar mensaje")
        if(!mine&&authorId.isNotBlank())options.add("✉ Enviar mensaje privado a $author")
        AlertDialog.Builder(this).setTitle("Administrar mensaje de $author")
            .setItems(options.toTypedArray()){_,which->
                when(which) {
                    0 -> showMessageDeleteOptions(messageId,mine)
                    1 -> if(authorId.isNotBlank()&&authorId!=me.optString("id"))createConversation(authorId,author,false)
                }
            }.show()
    }
    private fun showTappedMessageActions(obj:JSONObject) {
        val text=obj.optString("text")
        val media=obj.optJSONObject("media")
        val options=mutableListOf<String>()
        val actions=mutableListOf<()->Unit>()
        if(text.isNotBlank()) {
            options.add("Compartir mensaje")
            actions.add {
                val author=obj.optJSONObject("user")?.optString("name","Usuario")?:"Usuario"
                val share=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,"$author: $text")}
                startActivity(Intent.createChooser(share,"Compartir mensaje"))
            }
        }
        if(media!=null) {
            options.add("Abrir / reproducir archivo")
            actions.add {
                val mime=media.optString("mimeType").lowercase()
                when {
                    mime.startsWith("image/")->showImage(media)
                    mime.startsWith("video/")->playVideo(media)
                    mime.startsWith("audio/")->playAudio(media)
                    else->downloadMedia(media)
                }
            }
            options.add("Compartir archivo")
            actions.add {
                val path=media.optString("downloadPath")
                if(!path.startsWith("/api/uploads/")) {
                    toast("Este archivo no tiene una ruta válida para compartir")
                } else {
                    toast("Preparando archivo para compartir…")
                    Thread {
                        try {
                            val request=Request.Builder().url(baseUrl+path).header("Authorization","Bearer "+token).build()
                            http.newCall(request).execute().use { response ->
                                if(!response.isSuccessful)throw Exception("No se pudo descargar el archivo")
                                val bytes=response.body?.bytes()?:throw Exception("Archivo vacío")
                                val folder=File(cacheDir,"chatpro_share").apply{mkdirs()}
                                val safeName=media.optString("name","archivo").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"),"_").take(100).ifBlank{"archivo"}
                                val file=File(folder,safeName)
                                file.writeBytes(bytes)
                                val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.fileprovider",file)
                                val share=Intent(Intent.ACTION_SEND).apply {
                                    type=media.optString("mimeType").ifBlank{"application/octet-stream"}
                                    putExtra(Intent.EXTRA_STREAM,uri)
                                    clipData=android.content.ClipData.newUri(contentResolver,safeName,uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runOnUiThread{startActivity(Intent.createChooser(share,"Compartir archivo"))}
                            }
                        } catch(error:Exception) {
                            runOnUiThread{toast("No se pudo compartir: ${error.message}")}
                        }
                    }.start()
                }
            }
            options.add("Descargar archivo")
            actions.add{downloadMedia(media)}
        }
        options.add("🗑️ Borrar mensaje")
        val user=obj.optJSONObject("user")?:JSONObject()
        val mine=user.optString("id")==me.optString("id")
        actions.add {
            val messageId=obj.optString("id")
            if(messageId.isNotBlank()) showMessageDeleteOptions(messageId,mine)
        }
        AlertDialog.Builder(this).setTitle("Mensaje seleccionado")
            .setItems(options.toTypedArray()){_,which->actions[which].invoke()}
            .setNegativeButton("Cancelar",null).show()
    }
    private fun attachMessageLongPress(view:android.view.View,obj:JSONObject) {
        view.setOnLongClickListener {
            showMessageContextMenu(obj)
            true
        }
        if(view is android.view.ViewGroup) {
            for(i in 0 until view.childCount) attachMessageLongPress(view.getChildAt(i),obj)
        }
    }
    private fun showMessageContextMenu(obj:JSONObject) {
        val messageId=obj.optString("id")
        if(messageId.isBlank())return
        val user=obj.optJSONObject("user")?:JSONObject()
        val author=user.optString("name","Usuario")
        val authorId=user.optString("id")
        val mine=authorId.isNotBlank() && authorId==me.optString("id")
        val options=mutableListOf("😊 Reaccionar")
        if(!mine && authorId.isNotBlank()) options.add("💬 Enviar mensaje privado")
        AlertDialog.Builder(this).setTitle("Mensaje de $author")
            .setItems(options.toTypedArray()){_,which->
                if(which==0) showMessageReactions(messageId)
                else if(which==1 && authorId.isNotBlank() && authorId!=me.optString("id")) createConversation(authorId,author,false)
            }.setNegativeButton("Cancelar",null).show()
    }
    private fun renderConversationMessage(obj:JSONObject) {
        if(obj.optString("deletedAt").isNotBlank()) {
            content.addView(note("🚫 Este mensaje fue eliminado para todos"))
            return
        }
        val user=obj.optJSONObject("user")
        val who=user?.optString("name")?: "Usuario"
        val text=obj.optString("text")
        val mine=user?.optString("id")==me.optString("id")
        val receipt=if(mine){when{obj.optString("readAt").isNotBlank()->"  ✓✓";obj.optString("deliveredAt").isNotBlank()->"  ✓✓";else->"  ✓"}}else ""
        val card=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(12,9,12,9)
            background=GradientDrawable().apply {
                setColor(Color.argb(158,if(mine)35 else 17,if(mine)29 else 24,if(mine)66 else 43))
                cornerRadius=18f
                setStroke(2,if(mine)accent else Color.argb(190,210,220,240))
            }
            elevation=2f
        }
        content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(2,5,2,7)})
        val author=TextView(this).apply {
            this.text=who
            textSize=12f
            typeface=android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(accent)
            if(currentConversationType=="room"||currentConversationType=="group") {
                setOnClickListener { showRoomUserActions(user?.optString("id")?:"",who) }
                contentDescription="Opciones para $who"
        }
        }
        card.addView(author)
        if(text.isNotBlank())card.addView(TextView(this).apply {
            this.text="$text$receipt"
            textSize=15f
            setTextColor(Color.WHITE)
            setShadowLayer(2f,0f,1f,Color.BLACK)
        })
        val media=obj.optJSONObject("media")
        if(media!=null){
            val mediaName=media.optString("name","Archivo")
            val mime=media.optString("mimeType").lowercase()
            when {
                mime.startsWith("image/") -> {
                    val preview=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;maxHeight=(220*resources.displayMetrics.density).toInt();setBackgroundColor(Color.argb(55,255,255,255));setOnClickListener{showTappedMessageActions(obj)}}
                    card.addView(preview,LinearLayout.LayoutParams(-1,(220*resources.displayMetrics.density).toInt()))
                    loadImagePreview(media,preview)
                    card.addView(action("Ver imagen: $mediaName"){showTappedMessageActions(obj)})
                }
                mime.startsWith("video/") -> card.addView(action("▶  Reproducir video: $mediaName"){showTappedMessageActions(obj)})
                mime.startsWith("audio/") -> card.addView(action("▶  Reproducir música o audio: $mediaName"){showTappedMessageActions(obj)})
                else -> card.addView(note("📎 Archivo: $mediaName"))
            }
            card.contentDescription="Toca para compartir o descargar $mediaName"
        }
        val reactions=obj.optJSONArray("reactions")?:JSONArray()
        val messageId=obj.optString("id")
        if(messageId.isNotBlank()) {
            val reactionView=TextView(this).apply {
                textSize=13f
                setTextColor(Color.WHITE)
                setPadding(0,5,0,0)
            }
            card.addView(reactionView)
            reactionViews[messageId]=reactionView
            updateMessageReactionView(messageId,reactions)
        }
        if(messageId.isNotBlank()){
            // Mantener presionado abre solo Reaccionar o Mensaje privado. No incluye borrar.
            attachMessageLongPress(card,obj)
            card.setOnClickListener { showTappedMessageActions(obj) }
            card.contentDescription="Toca para compartir o borrar; mantén presionado para reaccionar o enviar mensaje privado"
        }
    }
    private fun editMessageDialog(messageId:String,original:String) {
        val input=field("Editar mensaje")
        input.setText(original)
        AlertDialog.Builder(this).setTitle("Editar mensaje").setView(input)
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                val value=input.text.toString().trim()
                if(value.isBlank()){toast("El mensaje no puede quedar vacío");return@setPositiveButton}
                api("PATCH","/api/messages/$messageId",JSONObject().put("text",value),{toast("Mensaje actualizado");refreshConversationMessages()},{toast("No se pudo editar: $it")})
            }.show()
    }
    private fun showMessageDeleteOptions(messageId:String,mine:Boolean) {
        val options=if(mine) arrayOf("Eliminar para mí","Eliminar para todos") else arrayOf("Eliminar para mí")
        AlertDialog.Builder(this).setTitle("Eliminar mensaje").setItems(options){_,which->
            val mode=if(mine&&which==1)"everyone" else "me"
            val explanation=if(mode=="everyone")"El mensaje desaparecerá para todos los participantes. Solo puedes hacerlo durante las primeras 48 horas." else "El mensaje se ocultará únicamente en tu cuenta."
            AlertDialog.Builder(this).setTitle(if(mode=="everyone")"¿Eliminar para todos?" else "¿Eliminar para mí?")
                .setMessage(explanation).setNegativeButton("Cancelar",null).setPositiveButton("Eliminar"){_,_->
                    api("POST","/api/messages/$messageId/delete",JSONObject().put("mode",mode),{toast(if(mode=="everyone")"Mensaje eliminado para todos" else "Mensaje eliminado para ti");refreshConversationMessages()},{toast("No se pudo eliminar: $it")})
                }.show()
        }.show()
    }
    private fun showMessageReactions(messageId:String) {
        // Selector explícito de reacciones. Ningún botón de este cuadro llama a borrar.
        val emojis=listOf("👍","❤️","😂","🔥","👏","😮","😢","🎉")
        val grid=android.widget.GridLayout(this).apply {
            columnCount=4
            setPadding(18,12,18,12)
        }
        val dialog=AlertDialog.Builder(this).setTitle("Elige una reacción").setView(grid)
            .setNegativeButton("Cancelar",null).create()
        emojis.forEach { emoji ->
            val button=TextView(this).apply {
                text=emoji
                textSize=30f
                gravity=Gravity.CENTER
                setPadding(12,12,12,12)
                contentDescription="Reaccionar con $emoji"
                setOnClickListener {
                    isEnabled=false
                    api("POST","/api/messages/$messageId/reactions",JSONObject().put("reaction",emoji),{data->
                        dialog.dismiss()
                        updateMessageReactions(messageId,data.optJSONArray("reactions")?:JSONArray())
                    },{error->
                        isEnabled=true
                        toast("No se pudo guardar la reacción: $error")
                    })
                }
            }
            grid.addView(button,android.widget.GridLayout.LayoutParams().apply {
                width=0
                height=(58*resources.displayMetrics.density).toInt()
                columnSpec=android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f)
            })
        }
        dialog.show()
    }
    private fun refreshConversationMessages() {
        val id=currentConversation
        if(id.isBlank())return
        api("GET","/api/conversations/$id/messages",null,{data->
            if(currentConversation!=id)return@api
            reactionViews.clear()
            content.removeAllViews()
            val arr=data.optJSONArray("messages")?:JSONArray()
            for(i in 0 until arr.length())renderConversationMessage(arr.optJSONObject(i)?:continue)
        },{header.text=it})
    }
    private fun updateMessageReactionView(messageId:String,reactions:JSONArray) {
        val view=reactionViews[messageId]?:return
        val summary=(0 until reactions.length())
            .mapNotNull{reactions.optJSONObject(it)?.optString("reaction")}
            .filter{it.isNotBlank()}
            .groupingBy{it}.eachCount().entries.joinToString("  "){ "${it.key} ${it.value}" }
        view.text=if(summary.isBlank())"" else "Reacciones: $summary"
        view.visibility=if(summary.isBlank())android.view.View.GONE else android.view.View.VISIBLE
    }
    private fun updateMessageReactions(messageId:String,reactions:JSONArray) {
        if(messageId.isBlank())return
        updateMessageReactionView(messageId,reactions)
    }
    private fun mediaUrl(media:JSONObject):String? {
        val path=media.optString("downloadPath")
        if(!path.startsWith("/api/uploads/"))return null
        return baseUrl+path+"?inline=1"
    }
    private fun loadImagePreview(media:JSONObject,target:ImageView) {
        val url=mediaUrl(media)?:return
        Thread {
            try {
                val request=Request.Builder().url(url).header("Authorization","Bearer "+token).build()
                http.newCall(request).execute().use { response ->
                    if(!response.isSuccessful)throw Exception("No se pudo cargar la imagen")
                    val bytes=response.body?.bytes()?:throw Exception("Imagen vacía")
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    var sample=1
                    while(bounds.outWidth/sample>1280||bounds.outHeight/sample>1280)sample*=2
                    val options=BitmapFactory.Options().apply{inSampleSize=sample}
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    runOnUiThread { if(bitmap!=null)target.setImageBitmap(bitmap) }
                }
            } catch(_:Exception) { runOnUiThread { target.setBackgroundColor(Color.argb(150,120,35,35)) } }
        }.start()
    }
    private fun showImage(media:JSONObject) {
        val image=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER}
        val box=ScrollView(this).apply{addView(image)}
        AlertDialog.Builder(this).setTitle(media.optString("name","Imagen")).setView(box).setPositiveButton("Cerrar",null).show()
        loadImagePreview(media,image)
    }
    private fun playVideo(media:JSONObject) {
        val url=mediaUrl(media)
        if(url==null){toast("Este video no tiene una ruta válida");return}
        val video=VideoView(this)
        video.layoutParams=LinearLayout.LayoutParams(-1,(240*resources.displayMetrics.density).toInt())
        val controller=MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        val dialog=AlertDialog.Builder(this).setTitle(media.optString("name","Video")).setView(video).setPositiveButton("Cerrar",null).create()
        dialog.setOnDismissListener { video.stopPlayback() }
        dialog.show()
        video.setOnPreparedListener { video.start() }
        video.setOnErrorListener { _,_,_ -> toast("No se pudo reproducir este video");true }
        video.setVideoURI(Uri.parse(url),mapOf("Authorization" to "Bearer "+token))
    }
    private fun playAudio(media:JSONObject) {
        val url=mediaUrl(media)
        if(url==null){toast("Este audio no tiene una ruta válida");return}
        try{
            audioPlayer?.release()
            val player=MediaPlayer()
            audioPlayer=player
            player.setDataSource(this,Uri.parse(url),mapOf("Authorization" to "Bearer "+token))
            player.setOnPreparedListener{it.start();toast("Reproduciendo "+media.optString("name","audio"))}
            player.setOnCompletionListener{it.release();if(audioPlayer===it)audioPlayer=null}
            player.setOnErrorListener{mp,_,_->mp.release();if(audioPlayer===mp)audioPlayer=null;toast("No se pudo reproducir este audio");true}
            player.prepareAsync()
        }catch(e:Exception){audioPlayer?.release();audioPlayer=null;toast("Error de reproducción: ${e.message}")}
    }
    private fun downloadMedia(media:JSONObject) {
        val path=media.optString("downloadPath")
        if(!path.startsWith("/api/uploads/")){toast("Este archivo no tiene una ruta válida");return}
        try {
            val safeName=media.optString("name","archivo").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"),"_").take(120).ifBlank{"archivo"}
            val request=DownloadManager.Request(Uri.parse(baseUrl+path))
                .addRequestHeader("Authorization","Bearer "+token)
                .setTitle(safeName)
                .setDescription("Descargando archivo de CHAT PRO")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"CHAT PRO/$safeName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val manager=getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast("Descarga iniciada. Se guardará en Descargas/CHAT PRO.")
        } catch(e:Exception) { toast("No se pudo iniciar la descarga: ${e.message}") }
    }
    private fun showStatuses() {
        content.removeAllViews();header.text="Los estados desaparecen después de 24 horas"
        val caption=field("¿Qué quieres compartir hoy?")
        content.addView(caption)
        content.addView(action("Publicar estado de texto"){val value=caption.text.toString().trim();if(value.isNotBlank())api("POST","/api/statuses",JSONObject().put("caption",value),{toast("Estado publicado");showHome("Estados")},{header.text=it})})
        content.addView(action("Publicar foto o video"){uploadMode="status";pendingStatusCaption=caption.text.toString().trim();val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/*","video/*"))};startActivityForResult(pick,44)})
        content.addView(action("Actualizar estados"){showStatuses()})
        api("GET","/api/statuses",null,{data->
            val arr=data.optJSONArray("statuses")?:JSONArray()
            if(arr.length()==0)content.addView(note("Todavía no hay estados publicados."))
            for(i in 0 until arr.length()){
                val s=arr.optJSONObject(i)?:continue
                val user=s.optJSONObject("user")
                val label="${user?.optString("name")?: "Usuario"}: ${s.optString("caption")}  ·  ${s.optInt("viewedBy")} vistas"
                val ownerId=user?.optString("id","")?: ""
                content.addView(action(label){
                    if(ownerId==me.optString("id")) api("GET","/api/statuses/${s.optString("id")}/views",null,{v->
                        val viewers=v.optJSONArray("viewers")?:JSONArray()
                        val names=(0 until viewers.length()).mapNotNull{viewers.optJSONObject(it)?.optJSONObject("user")?.optString("name")}.joinToString("\n")
                        AlertDialog.Builder(this).setTitle("Personas que vieron el estado").setMessage(if(names.isBlank())"Todavía nadie lo ha visto." else names).setPositiveButton("Cerrar",null).show()
                    },{toast(it)})
                    else api("POST","/api/statuses/${s.optString("id")}/views",JSONObject(),{toast("Vista registrada")},{toast(it)})
                })
                val statusMedia=s.optJSONObject("media")
                if(statusMedia!=null)content.addView(action("⬇ Descargar archivo del estado"){downloadMedia(statusMedia)})
                if(ownerId==me.optString("id"))content.addView(action("Eliminar mi estado"){api("DELETE","/api/statuses/${s.optString("id")}",null,{toast("Estado eliminado");showHome("Estados")},{toast(it)})})
            }
        },{header.text=it})
    }
    private fun showProfile() {
        content.removeAllViews();header.text="Tu cuenta y foto de perfil"
        val name=field(me.optString("name"));val about=field(me.optString("about"))
        val avatar=field(me.optString("avatarUrl"))
        content.addView(note("@"+me.optString("username")))
        content.addView(name);content.addView(about);content.addView(avatar)
        content.addView(action("Subir foto de perfil"){uploadMode="avatar";val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="image/*"};startActivityForResult(pick,43)})
        content.addView(action("Guardar perfil"){api("PATCH","/api/me",JSONObject().put("name",name.text.toString()).put("about",about.text.toString()).put("avatarUrl",avatar.text.toString()),{me=it.optJSONObject("user")?:me;toast("Perfil actualizado");showHome("Perfil")},{header.text=it})})
        content.addView(action("Cerrar sesión"){
            val fcmToken=getSharedPreferences("chatpro",MODE_PRIVATE).getString("fcm_token","")?:""
            val logoutAction={
                api("POST","/api/logout",JSONObject(),{token="";clearSecureToken();socket?.disconnect();callSocket?.disconnect();showAuth()},{header.text=it})
            }
            if(fcmToken.isBlank())logoutAction()
            else api("DELETE","/api/push/devices/"+Uri.encode(fcmToken),null,{logoutAction()},{logoutAction()})
        })
        content.addView(action("Vincular correo electrónico a esta cuenta"){showLinkEmailDialog()})
        content.addView(action("Cambiar contraseña"){showChangePasswordDialog()})
        content.addView(action("Personalizar apariencia"){showAppearance()})
        content.addView(action("🔒 Bloqueo de la aplicación con PIN"){showPinSettings()})
        content.addView(action("👆 Desbloqueo con huella digital"){showBiometricSettings()})
        content.addView(action("Cambiar servidor"){configureServer()})
    }
    private fun showLinkEmailDialog() {
        val email=field("Correo electrónico")
        email.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val password=field("Contraseña actual")
        password.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,4,16,4);addView(email);addView(passwordField(password))}
        AlertDialog.Builder(this).setTitle("Vincular correo a tu cuenta").setMessage("Confirma tu contraseña actual para asociar tu correo.")
            .setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Vincular"){_,_->
                api("POST","/api/me/email",JSONObject().put("email",email.text.toString()).put("currentPassword",password.text.toString()),{toast("Correo vinculado. Ya puedes entrar con tu correo.");showHome("Perfil")},{toast(it)})
            }.show()
    }
    private fun showChangePasswordDialog() {
        val current=field("Contraseña actual")
        val next=field("Nueva contraseña (mínimo 8 caracteres)")
        val confirm=field("Confirmar nueva contraseña")
        listOf(current,next,confirm).forEach{it.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,4,16,4);addView(passwordField(current));addView(passwordField(next));addView(passwordField(confirm))}
        AlertDialog.Builder(this).setTitle("Cambiar contraseña").setView(box)
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
                if(next.text.toString().length<8){toast("La nueva contraseña debe tener al menos 8 caracteres");return@setPositiveButton}
                if(next.text.toString()!=confirm.text.toString()){toast("Las contraseñas nuevas no coinciden");return@setPositiveButton}
                api("POST","/api/me/password",JSONObject().put("currentPassword",current.text.toString()).put("newPassword",next.text.toString()),{toast("Contraseña actualizada; las otras sesiones se cerraron")},{toast(it)})
            }.show()
    }
    private fun showAppearance() {
        content.removeAllViews()
        header.text="Personaliza CHAT PRO: el fondo oficial viene incluido para todos; tus cambios personales se guardan en este teléfono"
        content.addView(note("Tamaño de botones y texto"))
        content.addView(action("Ajustar tamaño de interfaz · actual: "+when(uiScale){0->"Compacto";2->"Grande";else->"Estándar"}){
            val choices=arrayOf("Compacto","Estándar","Grande")
            AlertDialog.Builder(this).setTitle("Tamaño de interfaz")
                .setSingleChoiceItems(choices,uiScale){dialog,which->
                    uiScale=which
                    getSharedPreferences("chatpro",MODE_PRIVATE).edit().putInt("ui_scale",uiScale).apply()
                    dialog.dismiss()
                    toast("Tamaño guardado en este teléfono")
                    showAppearance()
                }.setNegativeButton("Cancelar",null).show()
        })
        content.addView(note("El tamaño elegido se conserva al cerrar y volver a abrir CHAT PRO."))
        content.addView(note("Color de botones y detalles"))
        val colors=listOf("Violeta" to Color.rgb(154,111,255),"Azul" to Color.rgb(35,125,230),"Verde" to Color.rgb(24,150,110),"Rosa" to Color.rgb(210,75,145),"Naranja" to Color.rgb(220,125,35),"Turquesa" to Color.rgb(0,150,160))
        colors.forEach { (name,color)->
            content.addView(action("●  $name"){ accent=color;getSharedPreferences("chatpro",MODE_PRIVATE).edit().putInt("accent_color",color).apply();toast("Color guardado");showAppearance() })
        }
        content.addView(note("Fondos"))
        content.addView(action("Elegir imagen para pantalla principal"){chooseWallpaper("home_wallpaper")})
        if(currentConversation.isNotBlank()) content.addView(action("Elegir imagen para el chat actual"){chooseWallpaper("chat_wallpaper_"+currentConversation)})
        content.addView(action("Restablecer fondo oficial para todos"){getSharedPreferences("chatpro",MODE_PRIVATE).edit().remove("home_wallpaper").apply();applyWallpaper("home_wallpaper");toast("Fondo oficial de CHAT PRO restablecido")})
        if(currentConversation.isNotBlank()) content.addView(action("Quitar fondo del chat actual"){getSharedPreferences("chatpro",MODE_PRIVATE).edit().remove("chat_wallpaper_"+currentConversation).apply();applyWallpaper("chat_wallpaper_"+currentConversation);toast("Fondo del chat eliminado")})
        content.addView(action("Volver al perfil"){showHome("Perfil")})
    }
    private fun chooseFile() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/*","video/*","audio/*","application/pdf","application/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(intent,42) }
        catch (_:Exception) { toast("No se pudo abrir el selector de archivos") }
    }
    private fun displayName(uri:Uri):String {
        var result:String?=null
        try {
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                if(cursor.moveToFirst()) {
                    val index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(index>=0) result=cursor.getString(index)
                }
            }
        } catch (_:Exception) {}
        if(result.isNullOrBlank()) result=uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        return (result?: "archivo").substringAfterLast('/').ifBlank { "archivo" }
    }
    private fun extensionForMime(mime:String):String {
        return when(mime.lowercase().substringBefore(';')) {
            "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/webp" -> ".webp"
            "image/gif" -> ".gif"; "image/heic","image/heif" -> ".heic"; "image/bmp" -> ".bmp"
            "video/mp4" -> ".mp4"; "video/quicktime" -> ".mov"; "video/webm" -> ".webm"
            "video/3gpp" -> ".3gp"; "video/x-matroska" -> ".mkv"
            "audio/mpeg","audio/mp3" -> ".mp3"; "audio/mp4","audio/x-m4a" -> ".m4a"
            "audio/wav","audio/x-wav" -> ".wav"; "audio/aac" -> ".aac"
            "audio/ogg","application/ogg" -> ".ogg"; "audio/flac" -> ".flac"
            "audio/amr" -> ".amr"; "audio/opus" -> ".opus"
            "application/pdf" -> ".pdf"; else -> ""
        }
    }
    @Deprecated("Android compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode !in listOf(42,43,44,45)||resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        if(requestCode==45){ try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString(pendingWallpaperKey,uri.toString()).apply(); applyWallpaper(pendingWallpaperKey); toast("Fondo actualizado") } catch(_:Exception){toast("No se pudo guardar el fondo")}; return }
        val mime=(try { contentResolver.getType(uri) } catch (_:Exception) { null })?.lowercase()?.substringBefore(';') ?: "application/octet-stream"
        val originalName=displayName(uri)
        val inferredExt=extensionForMime(mime)
        val name=if(originalName.substringAfterLast('.', "").isNotBlank()) originalName else originalName+inferredExt
        header.text="Subiendo $name…"
        Thread{
            try{
                val safeName=name.replace(Regex("[^A-Za-z0-9._-]"),"_").ifBlank { "archivo"+inferredExt }
                val tmp=File(cacheDir,"chatpro_${System.currentTimeMillis()}_"+safeName)
                contentResolver.openInputStream(uri)?.use{src->tmp.outputStream().use{dst->src.copyTo(dst)}}?:throw Exception("Android no permitió leer este archivo. Intenta elegirlo desde la carpeta Archivos.")
                if(tmp.length()==0L) throw Exception("El archivo está vacío o no se pudo leer")
                val uploadMime=if(mime=="application/octet-stream" && inferredExt.isNotBlank()) when(inferredExt) {
                    ".jpg"->"image/jpeg"; ".png"->"image/png"; ".webp"->"image/webp"; ".mp4"->"video/mp4"
                    ".mov"->"video/quicktime"; ".mp3"->"audio/mpeg"; ".m4a"->"audio/mp4"; ".wav"->"audio/wav"
                    ".ogg"->"audio/ogg"; ".pdf"->"application/pdf"; else->mime
                } else mime
                val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file",safeName,tmp.asRequestBody(uploadMime.toMediaTypeOrNull())).build()
                val req=Request.Builder().url("$baseUrl/api/uploads").header("Authorization","Bearer $token").post(body).build()
                http.newCall(req).execute().use{res->
                    val data=JSONObject(res.body?.string()?: "{}");if(!res.isSuccessful)throw Exception(data.optString("error","Error de subida"))
                    val media=JSONObject().put("name",data.optString("name",name)).put("mimeType",data.optString("mimeType",mime)).put("size",data.optLong("size")).put("downloadPath",data.optString("downloadPath"))
                    runOnUiThread{
                        when(requestCode){
                            43 -> api("PATCH","/api/me",JSONObject().put("avatarUrl",baseUrl+data.optString("downloadPath")),{profile->
                                me=profile.optJSONObject("user")?:me
                                toast("Foto de perfil actualizada")
                                showHome("Perfil")
                            },{header.text=it})
                            44 -> api("POST","/api/statuses",JSONObject().put("caption",pendingStatusCaption).put("media",media),{
                                pendingStatusCaption=""
                                toast("Estado multimedia publicado")
                                showHome("Estados")
                            },{header.text=it})
                            else -> {
                                if(currentConversation.isNotBlank())socket?.emit("conversation:send",JSONObject().put("text","").put("media",media))
                                else toast("Archivo cargado")
                                header.text="Archivo cargado: $name"
                            }
                        }
                    }
                };tmp.delete()
            }catch(e:Exception){runOnUiThread{header.text=e.message?:"Error de subida"}}
        }.start()
    }
    private fun normalizeServerUrl(value:String):String {
        val raw=value.trim().trimEnd('/')
        require(raw.isNotBlank() && !raw.startsWith("/")) { "La dirección del servidor debe ser una URL completa." }
        val candidate=if(raw.startsWith("https://",true)||raw.startsWith("http://",true)) raw else "https://$raw"
        val parsed=Uri.parse(candidate)
        require(parsed.scheme=="https" || parsed.scheme=="http") { "El servidor debe comenzar con https:// o http://" }
        require(!parsed.host.isNullOrBlank()) { "La dirección del servidor no es válida." }
        return candidate
    }
    private fun configureServer() {
        val input=field(baseUrl)
        AlertDialog.Builder(this).setTitle("Servidor CHAT PRO").setView(input).setNegativeButton("Cancelar",null).setPositiveButton("Guardar"){_,_->
            try {
                baseUrl=normalizeServerUrl(input.text.toString())
                getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString("server_url",baseUrl).apply()
                if(token.isBlank())showAuth()else showHome("Chats")
            } catch(e:Exception) { toast(e.message?: "Dirección de servidor no válida") }
        }.show()
    }
    private fun chooseWallpaper(key:String) {
        pendingWallpaperKey=key
        val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="image/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        startActivityForResult(pick,45)
    }
    private fun applyWallpaper(key:String) {
        val raw=getSharedPreferences("chatpro",MODE_PRIVATE).getString(key,null)
        try {
            // The official CHAT PRO background is bundled in the APK so every user sees it.
            // A personal home wallpaper can override it; conversation backgrounds remain independent.
            val bitmap=if(!raw.isNullOrBlank()) {
                contentResolver.openInputStream(Uri.parse(raw))?.use { BitmapFactory.decodeStream(it) }
            } else if(key=="home_wallpaper") {
                BitmapFactory.decodeResource(resources,R.drawable.chatpro_wallpaper)
            } else null
            if(bitmap!=null) root.background=LayerDrawable(arrayOf(
                BitmapDrawable(resources,bitmap),
                ColorDrawable(Color.argb(112,8,11,24))
            )) else root.setBackgroundColor(bg)
        } catch(_:Exception) {
            if(key=="home_wallpaper") {
                val fallback=BitmapFactory.decodeResource(resources,R.drawable.chatpro_wallpaper)
                root.background=LayerDrawable(arrayOf(
                    BitmapDrawable(resources,fallback),
                    ColorDrawable(Color.argb(112,8,11,24))
                ))
            } else root.setBackgroundColor(bg)
        }
    }
    private fun passwordField(edit:EditText):LinearLayout {
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        row.addView(edit,LinearLayout.LayoutParams(0,-2,1f))
        val eye=TextView(this).apply{
            text="👁";textSize=21f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setPadding(12,12,12,12)
            contentDescription="Mostrar contraseña"
            setOnClickListener{
                val currentlyHidden=edit.transformationMethod is android.text.method.PasswordTransformationMethod
                val cursor=edit.selectionStart.coerceAtLeast(0)
                edit.transformationMethod=if(currentlyHidden) android.text.method.HideReturnsTransformationMethod.getInstance() else android.text.method.PasswordTransformationMethod.getInstance()
                text=if(currentlyHidden)"🙈" else "👁"
                contentDescription=if(currentlyHidden)"Ocultar contraseña" else "Mostrar contraseña"
                edit.setSelection(cursor.coerceAtMost(edit.text.length))
            }
        }
        row.addView(eye,LinearLayout.LayoutParams(52,56))
        return row
    }
    private fun uiScaleFactor()=when(uiScale){0->0.9f;2->1.15f;else->1f}
    private fun field(hint:String)=EditText(this).apply{
        val scale=uiScaleFactor()
        this.hint=hint;setHintTextColor(Color.LTGRAY);setTextColor(Color.WHITE)
        setPadding((16*scale).toInt(),(12*scale).toInt(),(16*scale).toInt(),(12*scale).toInt());minHeight=(56*scale).toInt();textSize=16f*scale
        setBackground(GradientDrawable().apply{setColor(Color.argb(245,32,44,51));cornerRadius=18f;setStroke(1,Color.rgb(65,82,91))})
    }
    private fun action(label:String,click:()->Unit)=Button(this).apply{
        val scale=uiScaleFactor()
        text=label;setTextColor(Color.WHITE);textSize=15f*scale;isAllCaps=false;minHeight=(54*scale).toInt()
        gravity=Gravity.CENTER_VERTICAL or Gravity.START;letterSpacing=0.005f;setPadding((18*scale).toInt(),(11*scale).toInt(),(16*scale).toInt(),(11*scale).toInt())
        stateListAnimator=null
        val edge=Color.argb(125,Color.red(accent),Color.green(accent),Color.blue(accent))
        val shape=GradientDrawable().apply{
            setColor(Color.argb(245,32,44,51))
            cornerRadius=18f
            setStroke(1,edge)
        }
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(55,255,255,255)),shape,null)
        elevation=2f
        setOnClickListener{click()}
    }
    private fun note(value:String)=TextView(this).apply{
        val scale=uiScaleFactor()
        text=value;textSize=14f*scale;setTextColor(Color.WHITE);setPadding((15*scale).toInt(),(13*scale).toInt(),(15*scale).toInt(),(13*scale).toInt())
        setBackground(GradientDrawable().apply{setColor(Color.argb(245,32,44,51));cornerRadius=18f;setStroke(1,Color.argb(115,Color.red(accent),Color.green(accent),Color.blue(accent)))})
        val lp=LinearLayout.LayoutParams(-1,-2);lp.setMargins(2,5,2,7);layoutParams=lp
    }
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()
    @Deprecated("Android back navigation")
    override fun onBackPressed() {
        val goBack=backAction
        if(goBack!=null) {
            backAction=null
            goBack.invoke()
        } else {
            super.onBackPressed()
        }
    }
    override fun onPause(){
        super.onPause()
        if(token.isNotBlank() && getSharedPreferences("chatpro",MODE_PRIVATE).getString("app_pin","").orEmpty().isNotBlank()) appNeedsUnlock=true
    }
    override fun onResume(){
        super.onResume()
        if(appNeedsUnlock && token.isNotBlank()) {
            appNeedsUnlock=false
            showSecurityGate { }
        }
    }
    override fun onDestroy(){
        socket?.disconnect();socket?.off()
        callSocket?.disconnect();callSocket?.off()
        audioPlayer?.release();audioPlayer=null
        super.onDestroy()
    }
}
