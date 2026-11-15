package com.djsebas.chatpro

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.media.MediaPlayer
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
    private var uploadMode="chat"
    private var pendingStatusCaption=""
    private var pendingWallpaperKey="home_wallpaper"
    private var socket:Socket?=null
    private var audioPlayer:MediaPlayer?=null
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
        if(token.isNotBlank()) loadMe() else showAuth()
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
        val username=field("Usuario")
        val password=field("Contraseña (mínimo 8 caracteres)")
        password.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        content.addView(name);content.addView(username);content.addView(password)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(action("Crear cuenta"){authRequest("/api/register",JSONObject().put("name",name.text.toString()).put("username",username.text.toString()).put("password",password.text.toString()))},LinearLayout.LayoutParams(0,54,1f))
        actions.addView(action("Iniciar sesión"){authRequest("/api/login",JSONObject().put("username",username.text.toString()).put("password",password.text.toString()))},LinearLayout.LayoutParams(0,54,1f))
        content.addView(actions)
        content.addView(action("Configurar servidor"){configureServer()})
        header.text="Crea una cuenta o inicia sesión. Cada persona necesita su propio usuario."
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
        frame("CHAT PRO",me.optString("name","Usuario")+" · @"+me.optString("username",""))
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("Chats","Contactos","Salas","Estados","Perfil").forEach{t->nav.addView(action(t){showHome(t)},LinearLayout.LayoutParams(0,48,1f))}
        root.addView(nav,1)
        root.addView(action("Cambiar fondo principal"){chooseWallpaper("home_wallpaper")},2)
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
                content.addView(action("✉  $title  ·  ${c.optString("type")}$unreadLabel"){openConversation(id,title)})
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
            openConversation(c.optString("id"),name)
        },{header.text=it})
    }
    private fun openConversation(id:String,title:String) {
        currentConversation=id;currentTitle=title
        frame(title,"Conversación protegida · CHAT PRO")
        header.text="Cargando historial…"
        composer=field("Escribe un mensaje")
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(action("＋"){uploadMode="chat";chooseFile()},LinearLayout.LayoutParams(54,52))
        row.addView(composer,LinearLayout.LayoutParams(0,52,1f))
        row.addView(action("Enviar"){sendMessage()},LinearLayout.LayoutParams(85,52))
        root.addView(row)
        root.addView(action("Cambiar fondo de este chat"){chooseWallpaper("chat_wallpaper_"+id)})
        root.addView(action("Agregar integrante al grupo o sala privada"){addConversationMemberDialog(id)})
        applyWallpaper("chat_wallpaper_"+id)
        api("GET","/api/conversations/$id/messages",null,{data->
            content.removeAllViews()
            val arr=data.optJSONArray("messages")?:JSONArray()
            for(i in 0 until arr.length())renderConversationMessage(arr.optJSONObject(i)?:continue)
            api("POST","/api/conversations/$id/read",JSONObject(),{},{})
            header.text="Conectando al chat en tiempo real…";connectConversation()
        },{header.text=it})
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
                runOnUiThread{renderConversationMessage(obj)}
            }
            socket?.on("message:reaction"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("conversation:read"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("conversation:delivered"){runOnUiThread{refreshConversationMessages()}}
            socket?.on("presence:joined"){runOnUiThread{header.text="En línea · $currentTitle"}}
            socket?.on("presence:left"){runOnUiThread{header.text="Miembro desconectado · $currentTitle"}}
            socket?.once(Socket.EVENT_CONNECT){socket?.emit("conversation:join",JSONObject().put("conversationId",currentConversation))}
            socket?.connect()
        }catch(e:Exception){header.text="Chat en línea no disponible: ${e.message}"}
    }
    private fun sendMessage() {
        val text=composer.text.toString().trim()
        if(text.isBlank())return
        val s=socket
        if(s==null||!s.connected()){toast("Espera a que el chat conecte");return}
        s.emit("conversation:send",JSONObject().put("text",text));composer.setText("")
    }
    private fun renderConversationMessage(obj:JSONObject) {
        val user=obj.optJSONObject("user")
        val who=user?.optString("name")?: "Usuario"
        val text=obj.optString("text")
        val mine=user?.optString("id")==me.optString("id")
        val receipt=if(mine){when{obj.optString("readAt").isNotBlank()->"  ✓✓";obj.optString("deliveredAt").isNotBlank()->"  ✓✓";else->"  ✓"}}else ""
        if(text.isNotBlank())content.addView(note("$who: $text$receipt"))
        val media=obj.optJSONObject("media")
        if(media!=null){
            val mediaName=media.optString("name","Archivo")
            if(media.optString("mimeType").startsWith("audio/"))content.addView(action("▶  Reproducir: $mediaName"){playAudio(media)})
            content.addView(action("⬇  Descargar: $mediaName"){downloadMedia(media)})
        }
        val reactions=obj.optJSONArray("reactions")?:JSONArray()
        val summary=(0 until reactions.length()).mapNotNull{reactions.optJSONObject(it)?.optString("reaction")}.groupingBy{it}.eachCount().entries.joinToString("  "){"${it.key} ${it.value}"}
        if(summary.isNotBlank())content.addView(note("Reacciones: $summary"))
        val messageId=obj.optString("id")
        if(messageId.isNotBlank())content.addView(action("Reaccionar  👍  ❤️  😂  🔥"){showMessageReactions(messageId)})
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
    private fun playAudio(media:JSONObject) {
        val path=media.optString("downloadPath")
        if(!path.startsWith("/api/uploads/")){toast("Este audio no tiene una ruta válida");return}
        try{
            audioPlayer?.release()
            val player=MediaPlayer()
            audioPlayer=player
            player.setDataSource(this,Uri.parse(baseUrl+path),mapOf("Authorization" to "Bearer "+token))
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
        content.addView(action("Personalizar apariencia"){showAppearance()})
        content.addView(action("Cambiar servidor"){configureServer()})
    }
    private fun showAppearance() {
        content.removeAllViews()
        header.text="Personaliza CHAT PRO: colores y fondos que se guardan en este teléfono"
        content.addView(note("Color de botones y detalles"))
        val colors=listOf("Violeta" to Color.rgb(154,111,255),"Azul" to Color.rgb(35,125,230),"Verde" to Color.rgb(24,150,110),"Rosa" to Color.rgb(210,75,145),"Naranja" to Color.rgb(220,125,35),"Turquesa" to Color.rgb(0,150,160))
        colors.forEach { (name,color)->
            content.addView(action("●  $name"){ accent=color;getSharedPreferences("chatpro",MODE_PRIVATE).edit().putInt("accent_color",color).apply();toast("Color guardado");showAppearance() })
        }
        content.addView(note("Fondos"))
        content.addView(action("Elegir imagen para pantalla principal"){chooseWallpaper("home_wallpaper")})
        if(currentConversation.isNotBlank()) content.addView(action("Elegir imagen para el chat actual"){chooseWallpaper("chat_wallpaper_"+currentConversation)})
        content.addView(action("Quitar fondo principal"){getSharedPreferences("chatpro",MODE_PRIVATE).edit().remove("home_wallpaper").apply();applyWallpaper("home_wallpaper");toast("Fondo principal eliminado")})
        if(currentConversation.isNotBlank()) content.addView(action("Quitar fondo del chat actual"){getSharedPreferences("chatpro",MODE_PRIVATE).edit().remove("chat_wallpaper_"+currentConversation).apply();applyWallpaper("chat_wallpaper_"+currentConversation);toast("Fondo del chat eliminado")})
        content.addView(action("Volver al perfil"){showHome("Perfil")})
    }
    private fun chooseFile() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"}
        startActivityForResult(intent,42)
    }
    @Deprecated("Android compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode !in listOf(42,43,44,45)||resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        if(requestCode==45){ try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString(pendingWallpaperKey,uri.toString()).apply(); applyWallpaper(pendingWallpaperKey); toast("Fondo actualizado") } catch(_:Exception){toast("No se pudo guardar el fondo")}; return }
        val name=uri.lastPathSegment?.substringAfterLast('/')?:"archivo"
        val mime=contentResolver.getType(uri)?:"application/octet-stream"
        header.text="Subiendo $name…"
        Thread{
            try{
                val tmp=File(cacheDir,"chatpro_${System.currentTimeMillis()}_"+name.replace(Regex("[^A-Za-z0-9._-]"),"_"))
                contentResolver.openInputStream(uri)?.use{src->tmp.outputStream().use{dst->src.copyTo(dst)}}?:throw Exception("No se pudo leer el archivo")
                val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file",name,tmp.asRequestBody(mime.toMediaTypeOrNull())).build()
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
        if(raw.isNullOrBlank()){root.setBackgroundColor(bg);return}
        try {
            val bitmap=contentResolver.openInputStream(Uri.parse(raw))?.use { BitmapFactory.decodeStream(it) }
            if(bitmap!=null) root.background=LayerDrawable(arrayOf(
                BitmapDrawable(resources,bitmap),
                ColorDrawable(Color.argb(112,8,11,24))
            )) else root.setBackgroundColor(bg)
        } catch(_:Exception){root.setBackgroundColor(bg)}
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
    override fun onDestroy(){socket?.disconnect();socket?.off();audioPlayer?.release();audioPlayer=null;super.onDestroy()}
}
