package com.djsebas.chatpro

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
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

class MainActivity : Activity() {
    private val bg=Color.rgb(13,16,29)
    private val panel=Color.rgb(25,30,49)
    private var accent=Color.rgb(154,111,255)
    private val http=OkHttpClient.Builder().connectTimeout(25,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).build()
    private val defaultBaseUrl="https://chat-pro-server-qhjw.onrender.com"
    private var baseUrl=defaultBaseUrl
    private var token=""
    private var me=JSONObject()
    private var currentConversation=""
    private var currentTitle=""
    private var currentPeerId=""
    private var uploadMode="chat"
    private var pendingStatusCaption=""
    private var pendingWallpaperKey="home_wallpaper"
    private var socket:Socket?=null
    private var callSocket:Socket?=null
    private var audioPlayer:MediaPlayer?=null
    private var voiceRecorder:MediaRecorder?=null
    private var voiceFile:File?=null
    private var isRecordingVoice=false
    private var isActivityVisible=false
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
        accent=p.getInt("accent_color",Color.rgb(154,111,255))
        token=p.getString("token","")?:""
        createChatNotificationChannel()
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),901)
        if(p.getString("pin_hash",null)!=null) showAppLock() else if(token.isNotBlank()) loadMe() else showAuth()
    }

    private fun frame(title:String,subtitle:String="") {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(14,10,14,10) }
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
        content.addView(name);content.addView(email);content.addView(password)
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
                    getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString("token",token).apply()
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
    private fun showHome(tab:String) {
        socket?.disconnect()
        connectCallSocket()
        // Diseño principal: navegación vertical a la izquierda y contenido a la derecha.
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(10,8,10,8)
        }
        root.addView(TextView(this).apply {
            text="CHAT PRO";textSize=25f;gravity=Gravity.CENTER
            setTextColor(Color.WHITE);setPadding(4,8,4,2)
        })
        root.addView(TextView(this).apply {
            text=me.optString("name","Usuario")+" · @"+me.optString("username","")
            textSize=12f;gravity=Gravity.CENTER;setTextColor(accent)
            setPadding(4,0,4,7)
        })
        header=TextView(this).apply {
            text="";textSize=13f;setTextColor(Color.LTGRAY)
            setPadding(4,6,4,8)
        }
        root.addView(header)
        val workspace=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
        }
        val nav=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(0,2,7,2)
        }
        val navWidth=(112*resources.displayMetrics.density).toInt()
        listOf(
            "💬\nChats" to "Chats",
            "👥\nContactos" to "Contactos",
            "🌐\nSalas" to "Salas",
            "🟣\nEstados" to "Estados",
            "👤\nPerfil" to "Perfil"
        ).forEach { (label,destination) ->
            val item=action(label){showHome(destination)}
            item.textSize=14f
            item.minHeight=68
            item.setPadding(4,8,4,8)
            nav.addView(item,LinearLayout.LayoutParams(-1,0,1f).apply {
                setMargins(0,3,0,3)
            })
        }
        val mainScroll=ScrollView(this)
        content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(2,4,2,10)
        }
        mainScroll.addView(content)
        workspace.addView(nav,LinearLayout.LayoutParams(navWidth,-1))
        workspace.addView(mainScroll,LinearLayout.LayoutParams(0,-1,1f))
        root.addView(workspace,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(action("🖼  Cambiar fondo principal"){
            chooseWallpaper("home_wallpaper")
        })
        setContentView(root)
        applyWallpaper("home_wallpaper")
        when(tab){
            "Chats"->loadConversations()
            "Contactos"->showContacts()
            "Salas"->showPublicRooms()
            "Estados"->showStatuses()
            "Perfil"->showProfile()
        }
    }
    private fun loadConversations() {
        header.text="Tus conversaciones privadas y grupos"
        api("GET","/api/conversations",null,{data->
            content.removeAllViews()
            val arr=data.optJSONArray("conversations")?:JSONArray()
            if(arr.length()==0)content.addView(note("Aún no tienes chats. Entra a Contactos para buscar personas."))
            for(i in 0 until arr.length()){
                val c=arr.optJSONObject(i)?:continue
                val id=c.optString("id")
                val title=c.optString("title").ifBlank{if(c.optString("type")=="group")"Grupo" else "Chat privado"}
                val unread=c.optInt("unread",0)
                val unreadLabel=if(unread>0)"  ·  $unread sin leer" else ""
                val memberIds=c.optJSONArray("members")?:JSONArray()
                val myId=me.optString("id")
                val peerId=(0 until memberIds.length()).map{memberIds.optString(it)}.firstOrNull{it.isNotBlank()&&it!=myId}?: ""
                content.addView(action("✉  $title  ·  ${c.optString("type")}$unreadLabel"){openConversation(id,title,peerId,c.optString("type"))})
            }
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
                api("POST","/api/conversations",body,{data->val room=data.optJSONObject("conversation")?:return@api;openConversation(room.optString("id"),room.optString("title","Sala"))},{header.text=it})
            }.show()
    }
    private fun showPublicRooms() {
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
                    if(joined)openConversation(id,title)
                    else api("POST","/api/rooms/$id/join",JSONObject(),{openConversation(id,title)},{toast(it)})
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
                api("POST","/api/conversations",body,{data->val conv=data.optJSONObject("conversation")?:return@api;openConversation(conv.optString("id"),conv.optString("title","Grupo"))},{header.text=it})
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
    private fun createConversation(userId:String,name:String) {
        api("POST","/api/conversations",JSONObject().put("members",JSONArray().put(userId)),{data->
            val c=data.optJSONObject("conversation")?:return@api
            openConversation(c.optString("id"),name,userId,"direct")
        },{header.text=it})
    }
    private fun openConversation(id:String,title:String,peerId:String="",conversationType:String="direct") {
        currentConversation=id;currentTitle=title;currentPeerId=if(conversationType=="direct")peerId else ""
        frame(title,"Conversación protegida · CHAT PRO")
        header.text="Cargando historial…"
        composer=field("Escribe un mensaje")
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(action("🎙"){toggleVoiceRecording()},LinearLayout.LayoutParams(58,52))
        row.addView(action("＋"){uploadMode="chat";chooseFile()},LinearLayout.LayoutParams(54,52))
        row.addView(composer,LinearLayout.LayoutParams(0,52,1f))
        row.addView(action("Enviar"){sendMessage()},LinearLayout.LayoutParams(85,52))
        root.addView(row)
        if(conversationType=="direct"&&currentPeerId.isNotBlank()){
            val callRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            callRow.addView(action("📞 Llamada de voz"){startCall("audio")},LinearLayout.LayoutParams(0,54,1f))
            callRow.addView(action("🎥 Videollamada"){startCall("video")},LinearLayout.LayoutParams(0,54,1f))
            root.addView(callRow)
        }
        root.addView(action("Cambiar fondo de este chat"){chooseWallpaper("chat_wallpaper_"+id)})
        root.addView(action("Agregar integrante al grupo o sala privada"){addConversationMemberDialog(id)})
        root.addView(action("🧹 Limpiar historial"){confirmClearConversationHistory(id)})
        applyWallpaper("chat_wallpaper_"+id)
        api("GET","/api/conversations/$id/messages",null,{data->
            content.removeAllViews()
            val arr=data.optJSONArray("messages")?:JSONArray()
            for(i in 0 until arr.length())renderConversationMessage(arr.optJSONObject(i)?:continue)
            api("POST","/api/conversations/$id/read",JSONObject(),{},{})
            header.text="Conectando al chat en tiempo real…";connectConversation()
        },{header.text=it})
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
    private fun addConversationMemberDialog(conversationId:String) {
        val username=field("Nombre de usuario a invitar")
        AlertDialog.Builder(this).setTitle("Agregar integrante").setMessage("Solo el creador del grupo o sala puede agregar participantes.").setView(username)
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
            callSocket?.on("notification:new"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                if(!isActivityVisible || obj.optString("conversationId")!=currentConversation) showChatNotification(obj)
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
        try {
            socket=IO.socket(baseUrl,IO.Options.builder().setAuth(mapOf("token" to token)).setReconnection(true).setTimeout(30000).build())
            socket?.on(Socket.EVENT_CONNECT){runOnUiThread{header.text="En línea · $currentTitle"}}
            socket?.on("conversation:history"){args->
                val a=args.firstOrNull() as? JSONArray?:return@on
                runOnUiThread{content.removeAllViews();for(i in 0 until a.length())renderConversationMessage(a.optJSONObject(i)?:continue)}
            }
            socket?.on("conversation:message"){args->
                val obj=args.firstOrNull() as? JSONObject?:return@on
                if(!isActivityVisible || obj.optString("conversationId")!=currentConversation) showChatNotification(obj)
                runOnUiThread{renderConversationMessage(obj)}
            }
            socket?.on("message:reaction"){runOnUiThread{refreshConversationMessages()}}
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
        if(user!=null){
            val senderLabel=note("$who · tocar para ver opciones")
            senderLabel.setOnClickListener{showRoomUserActions(user.optString("id"),who)}
            content.addView(senderLabel)
        }
        if(text.isNotBlank())content.addView(note(text+receipt))
        val media=obj.optJSONObject("media")
        if(media!=null){
            val mediaName=media.optString("name","Archivo")
            val mime=media.optString("mimeType").lowercase()
            when {
                mime.startsWith("image/") -> {
                    val preview=ImageView(this).apply {
                        adjustViewBounds=true
                        scaleType=ImageView.ScaleType.FIT_CENTER
                        maxHeight=(220*resources.displayMetrics.density).toInt()
                        setBackgroundColor(Color.argb(90,255,255,255))
                    }
                    content.addView(preview,LinearLayout.LayoutParams(-1,(220*resources.displayMetrics.density).toInt()))
                    loadImagePreview(media,preview)
                    content.addView(action("Ver imagen: $mediaName"){showImage(media)})
                }
                mime.startsWith("video/") -> content.addView(action("▶  Reproducir video: $mediaName"){playVideo(media)})
                mime.startsWith("audio/") -> content.addView(action("▶  Reproducir música o audio: $mediaName"){playAudio(media)})
                else -> content.addView(note("📎 Archivo: $mediaName"))
            }
            content.addView(action("⬇  Descargar: $mediaName"){downloadMedia(media)})
        }
        val reactions=obj.optJSONArray("reactions")?:JSONArray()
        val summary=(0 until reactions.length()).mapNotNull{reactions.optJSONObject(it)?.optString("reaction")}.groupingBy{it}.eachCount().entries.joinToString("  "){"${it.key} ${it.value}"}
        if(summary.isNotBlank())content.addView(note("Reacciones: $summary"))
        val messageId=obj.optString("id")
        if(messageId.isNotBlank()) {
            content.addView(action("Reaccionar  👍  ❤️  😂  🔥"){showMessageReactions(messageId)})
            content.addView(action("⋮  Opciones del mensaje"){showMessageDeleteOptions(messageId,mine)})
        }
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
        val options=arrayOf("👍","❤️","😂","🔥","👏","😮","😢","🎉")
        AlertDialog.Builder(this).setTitle("Reaccionar al mensaje").setItems(options){_,which->
            api("POST","/api/messages/$messageId/reactions",JSONObject().put("reaction",options[which]),{refreshConversationMessages()},{toast(it)})
        }.show()
    }
    private fun refreshConversationMessages() {
        val id=currentConversation
        if(id.isBlank())return
        api("GET","/api/conversations/$id/messages",null,{data->
            if(currentConversation!=id)return@api
            content.removeAllViews()
            val arr=data.optJSONArray("messages")?:JSONArray()
            for(i in 0 until arr.length())renderConversationMessage(arr.optJSONObject(i)?:continue)
        },{header.text=it})
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
        content.addView(action("Cerrar sesión"){api("POST","/api/logout",JSONObject(),{token="";getSharedPreferences("chatpro",MODE_PRIVATE).edit().remove("token").apply();socket?.disconnect();showAuth()},{header.text=it})})
        content.addView(action("Vincular correo electrónico a esta cuenta"){showLinkEmailDialog()})
        content.addView(action("Cambiar contraseña"){showChangePasswordDialog()})
        content.addView(action("🔐 Configurar bloqueo PIN"){showPinSetup()})
        if(getSharedPreferences("chatpro",MODE_PRIVATE).getString("pin_hash",null)!=null) content.addView(action("Desactivar bloqueo PIN"){confirmDisableAppLock()})
        content.addView(action("Personalizar apariencia"){showAppearance()})
        content.addView(action("Cambiar servidor"){configureServer()})
    }

    private fun showRoomUserActions(userId:String,name:String) {
        if(userId.isBlank() || userId==me.optString("id")) return
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf("Iniciar chat privado","Compartir audio, imagen o archivo en esta sala")){_,which->
                if(which==0) createConversation(userId,name)
                else { uploadMode="chat"; chooseFile() }
            }.show()
    }

    private fun toggleVoiceRecording() {
        if(isRecordingVoice) {
            try {
                voiceRecorder?.stop()
                voiceRecorder?.release()
                voiceRecorder=null
                isRecordingVoice=false
                val file=voiceFile
                voiceFile=null
                if(file!=null && file.exists() && file.length()>0) uploadVoiceNote(file)
                else toast("No se pudo grabar el mensaje de voz")
            } catch(e:Exception) {
                voiceRecorder?.release();voiceRecorder=null;isRecordingVoice=false
                voiceFile?.delete();voiceFile=null
                toast("No se pudo finalizar la grabación")
            }
            return
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),902)
            toast("Permite el micrófono y vuelve a tocar el botón de voz")
            return
        }
        try {
            val file=File(cacheDir,"chatpro_voice_"+System.currentTimeMillis()+".m4a")
            val recorder=if(Build.VERSION.SDK_INT>=31) MediaRecorder(this) else MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare();recorder.start()
            voiceFile=file;voiceRecorder=recorder;isRecordingVoice=true
            toast("Grabando… vuelve a tocar el micrófono para enviar")
        } catch(e:Exception) {
            voiceRecorder?.release();voiceRecorder=null;isRecordingVoice=false
            voiceFile?.delete();voiceFile=null
            toast("No se pudo iniciar la grabación")
        }
    }

    private fun uploadVoiceNote(file:File) {
        if(currentConversation.isBlank() || socket?.connected()!=true) {
            file.delete();toast("Conecta primero con el chat para enviar la nota de voz");return
        }
        header.text="Subiendo mensaje de voz…"
        Thread {
            try {
                val body=MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file",file.name,file.asRequestBody("audio/mp4".toMediaTypeOrNull())).build()
                val req=Request.Builder().url(baseUrl+"/api/uploads").header("Authorization","Bearer "+token).post(body).build()
                http.newCall(req).execute().use { response ->
                    val data=JSONObject(response.body?.string()?: "{}")
                    if(!response.isSuccessful) throw Exception(data.optString("error","No se pudo subir el audio"))
                    val media=JSONObject().put("name",data.optString("name",file.name))
                        .put("mimeType",data.optString("mimeType","audio/mp4"))
                        .put("size",data.optLong("size")).put("downloadPath",data.optString("downloadPath"))
                    runOnUiThread {
                        socket?.emit("conversation:send",JSONObject().put("text","").put("media",media))
                        header.text="Mensaje de voz enviado"
                    }
                }
            } catch(e:Exception) { runOnUiThread{header.text=e.message?: "Error al enviar la nota de voz"} }
            finally { file.delete() }
        }.start()
    }

    private fun createChatNotificationChannel() {
        if(Build.VERSION.SDK_INT>=26) {
            val channel=NotificationChannel("chat_pro_messages","Mensajes de CHAT PRO",NotificationManager.IMPORTANCE_HIGH)
            channel.description="Avisos de mensajes privados y salas"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showChatNotification(message:JSONObject) {
        try {
            val sender=message.optJSONObject("user")?.optString("name")?.ifBlank{"Nuevo mensaje"}?: "Nuevo mensaje"
            val text=message.optString("text").ifBlank {
                if(message.optJSONObject("media")!=null) "Te envió un archivo o mensaje de voz" else "Tienes un mensaje nuevo"
            }
            val intent=Intent(this,MainActivity::class.java).apply { flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
            val pending=PendingIntent.getActivity(this,42,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder=if(Build.VERSION.SDK_INT>=26) android.app.Notification.Builder(this,"chat_pro_messages") else android.app.Notification.Builder(this)
            val notification=builder.setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("CHAT PRO · "+sender).setContentText(text.take(120))
                .setAutoCancel(true).setContentIntent(pending).build()
            getSystemService(NotificationManager::class.java).notify((message.optString("id").hashCode() and 0x7fffffff),notification)
        } catch(_:Exception) { }
    }

    private fun makePinHash(pin:String,salt:String):String {
        val digest=MessageDigest.getInstance("SHA-256")
        val bytes=digest.digest((salt+":"+pin).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes,Base64.NO_WRAP)
    }

    private fun showPinSetup() {
        val pin=field("Nuevo PIN (mínimo 6 dígitos)")
        val confirm=field("Confirmar PIN")
        pin.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        confirm.inputType=pin.inputType
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,4,16,4);addView(pin);addView(confirm)}
        AlertDialog.Builder(this).setTitle("Bloqueo de CHAT PRO").setMessage("El PIN se protege con hash y sal local en este teléfono.")
            .setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar PIN"){_,_->
                val a=pin.text.toString();val b=confirm.text.toString()
                if(a.length<6 || !a.all{it.isDigit()}) { toast("El PIN debe tener al menos 6 dígitos");return@setPositiveButton }
                if(a!=b) { toast("Los PIN no coinciden");return@setPositiveButton }
                val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
                val saltText=Base64.encodeToString(salt,Base64.NO_WRAP)
                getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString("pin_salt",saltText).putString("pin_hash",makePinHash(a,saltText)).apply()
                toast("Bloqueo PIN activado. La huella también estará disponible si el teléfono la admite.")
                showHome("Perfil")
            }.show()
    }

    private fun showAppLock() {
        frame("CHAT PRO","Desbloqueo de seguridad")
        val pin=field("Introduce tu PIN")
        pin.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        content.addView(pin)
        content.addView(action("Desbloquear con PIN"){
            val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
            val salt=prefs.getString("pin_salt",null)
            val expected=prefs.getString("pin_hash",null)
            if(salt!=null && expected!=null && makePinHash(pin.text.toString(),salt)==expected) unlockAfterAppLock()
            else toast("PIN incorrecto")
        })
        content.addView(action("Usar huella o biometría del teléfono"){showBiometricUnlock()})
        header.text="Acceso protegido a tus conversaciones."
    }

    private fun showBiometricUnlock() {
        try {
            val executor=ContextCompat.getMainExecutor(this)
            val prompt=BiometricPrompt(this,executor,object:BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result);unlockAfterAppLock()
                }
                override fun onAuthenticationError(errorCode:Int,errString:CharSequence) {
                    super.onAuthenticationError(errorCode,errString);toast(errString.toString())
                }
            })
            val info=BiometricPrompt.PromptInfo.Builder().setTitle("Desbloquear CHAT PRO")
                .setSubtitle("Usa tu huella o biometría configurada").setNegativeButtonText("Cancelar").build()
            prompt.authenticate(info)
        } catch(e:Exception) { toast("La biometría no está disponible en este teléfono") }
    }

    private fun unlockAfterAppLock() {
        token=getSharedPreferences("chatpro",MODE_PRIVATE).getString("token","")?: ""
        if(token.isNotBlank()) loadMe() else showAuth()
    }

    private fun confirmDisableAppLock() {
        AlertDialog.Builder(this).setTitle("Desactivar bloqueo")
            .setMessage("CHAT PRO dejará de pedir PIN o huella al abrirse.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Desactivar"){_,_->
                getSharedPreferences("chatpro",MODE_PRIVATE).edit().remove("pin_hash").remove("pin_salt").apply()
                toast("Bloqueo desactivado");showHome("Perfil")
            }.show()
    }

    override fun onResume() { super.onResume();isActivityVisible=true }
    override fun onPause() { isActivityVisible=false;super.onPause() }

    private fun showLinkEmailDialog() {
        val email=field("Correo electrónico")
        email.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val password=field("Contraseña actual")
        password.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,4,16,4);addView(email);addView(password)}
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
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,4,16,4);addView(current);addView(next);addView(confirm)}
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
    private fun field(hint:String)=EditText(this).apply{
        this.hint=hint;setHintTextColor(Color.LTGRAY);setTextColor(Color.WHITE)
        setPadding(16,12,16,12);minHeight=56;textSize=16f
        setBackground(GradientDrawable().apply{setColor(Color.argb(235,25,30,49));cornerRadius=18f;setStroke(1,Color.rgb(85,96,128))})
    }
    private fun action(label:String,click:()->Unit)=Button(this).apply{
        text=label;setTextColor(Color.WHITE);textSize=15f;isAllCaps=false;minHeight=58
        gravity=Gravity.CENTER;letterSpacing=0.015f;setPadding(18,12,18,12)
        stateListAnimator=null
        val top=Color.rgb((Color.red(accent)+28).coerceAtMost(255),(Color.green(accent)+28).coerceAtMost(255),(Color.blue(accent)+28).coerceAtMost(255))
        val shape=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(top,accent)).apply{
            cornerRadius=20f;setStroke(1,Color.argb(190,225,220,255))
        }
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(75,255,255,255)),shape,null)
        elevation=5f
        setOnClickListener{click()}
    }
    private fun note(value:String)=TextView(this).apply{
        text=value;textSize=14f;setTextColor(Color.WHITE);setPadding(15,13,15,13)
        setBackground(GradientDrawable().apply{setColor(Color.argb(220,25,30,49));cornerRadius=16f;setStroke(1,Color.argb(150,90,105,140))})
        val lp=LinearLayout.LayoutParams(-1,-2);lp.setMargins(2,5,2,7);layoutParams=lp
    }
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()
    override fun onDestroy(){
        socket?.disconnect();socket?.off()
        callSocket?.disconnect();callSocket?.off()
        audioPlayer?.release();audioPlayer=null
        voiceRecorder?.release();voiceRecorder=null
        voiceFile?.delete();voiceFile=null
        super.onDestroy()
    }
}
