package com.djsebas.chatpro

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val ink = Color.rgb(13,16,29)
    private val panel = Color.rgb(25,30,49)
    private val purple = Color.rgb(154,111,255)
    private val http = OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).build()
    private lateinit var feed: LinearLayout
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var socket: Socket? = null
    private val mediaPlayers = mutableListOf<MediaPlayer>()
    private var serverUrl = ""
    private var roomId = "general"
    private var displayName = "DJ Sebas"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs=getSharedPreferences("chatpro",MODE_PRIVATE)
        serverUrl=prefs.getString("server_url","") ?: ""
        displayName=prefs.getString("display_name","DJ Sebas") ?: "DJ Sebas"
        roomId=prefs.getString("room_id","general") ?: "general"
        window.statusBarColor=ink; window.navigationBarColor=ink
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(ink);setPadding(14,12,14,10) }
        root.addView(TextView(this).apply { text="CHAT PRO";textSize=25f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(4,8,4,2) })
        root.addView(TextView(this).apply { text="Comparte tus mezclas terminadas";textSize=13f;setTextColor(purple);gravity=Gravity.CENTER;setPadding(4,0,4,10) })
        val toolbar=LinearLayout(this).apply { gravity=Gravity.CENTER;orientation=LinearLayout.HORIZONTAL }
        toolbar.addView(Button(this).apply { text="Servidor";setOnClickListener { configure() } })
        toolbar.addView(Button(this).apply { text="Sala";setOnClickListener { configureRoom() } })
        toolbar.addView(Button(this).apply { text="Reconectar";setOnClickListener { connect() } })
        root.addView(toolbar)
        status=TextView(this).apply { text="Sin conectar · configura la dirección del servidor";setTextColor(Color.LTGRAY);textSize=12f;setPadding(6,5,6,8) }
        root.addView(status)
        feed=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(3,4,3,12) }
        val scroll=ScrollView(this);scroll.addView(feed)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply { text="Fotos · Videos · MP3 · WAV · M4A · FLAC · Documentos";textSize=11f;setTextColor(Color.LTGRAY);setPadding(3,7,3,7) })
        val composer=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        composer.addView(Button(this).apply { text="＋";setOnClickListener { chooseFile() } })
        input=EditText(this).apply { hint="Escribe un mensaje";setHintTextColor(Color.GRAY);setTextColor(Color.WHITE);setSingleLine(true);setPadding(10,3,10,3);setBackgroundColor(panel) }
        composer.addView(input,LinearLayout.LayoutParams(0,52,1f))
        composer.addView(Button(this).apply { text="Enviar";setOnClickListener { sendText() } })
        root.addView(composer)
        setContentView(root)
        addBubble("CHAT PRO está listo. Configura la URL de tu servidor para conversar y compartir archivos.",false)
        if(serverUrl.isNotBlank()) connect() else configure()
    }

    private fun configure() {
        val field=EditText(this).apply { setText(serverUrl);hint="https://tu-servidor.onrender.com";setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Servidor CHAT PRO").setMessage("Pega la URL HTTPS del servidor desplegado.").setView(field)
            .setNegativeButton("Cancelar",null).setPositiveButton("Guardar") { _,_ ->
                serverUrl=field.text.toString().trim().trimEnd('/')
                getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString("server_url",serverUrl).apply()
                connect()
            }.show()
    }

    private fun configureRoom() {
        val name=EditText(this).apply { setText(displayName);hint="Tu nombre" }
        val room=EditText(this).apply { setText(roomId);hint="Nombre de sala" }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(20,4,20,4);addView(name);addView(room) }
        AlertDialog.Builder(this).setTitle("Tu perfil y sala").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar") { _,_ ->
            displayName=name.text.toString().trim().ifBlank { "Usuario" }.take(40)
            roomId=room.text.toString().trim().ifBlank { "general" }.take(80)
            getSharedPreferences("chatpro",MODE_PRIVATE).edit().putString("display_name",displayName).putString("room_id",roomId).apply()
            connect()
        }.show()
    }

    private fun connect() {
        if(serverUrl.isBlank()) { status.text="Falta configurar el servidor";return }
        try {
            socket?.disconnect()
            val opts=IO.Options.builder().setReconnection(true).setReconnectionAttempts(Int.MAX_VALUE).build()
            socket=IO.socket(serverUrl,opts)
            socket?.on(Socket.EVENT_CONNECT) {
                runOnUiThread { status.text="Conectado · sala: $roomId";status.setTextColor(Color.GREEN) }
                socket?.emit("room:join",JSONObject().put("roomId",roomId).put("displayName",displayName))
            }
            socket?.on("room:history") { args ->
                val arr=args.firstOrNull() as? org.json.JSONArray ?: return@on
                runOnUiThread { feed.removeAllViews();for(i in 0 until arr.length()) renderMessage(arr.optJSONObject(i) ?: continue) }
            }
            socket?.on("message:new") { args ->
                val obj=args.firstOrNull() as? JSONObject ?: return@on
                runOnUiThread { renderMessage(obj) }
            }
            socket?.on(Socket.EVENT_CONNECT_ERROR) {
                runOnUiThread { status.text="No se pudo conectar. Revisa la URL y el servidor.";status.setTextColor(Color.RED) }
            }
            socket?.connect()
            status.text="Conectando a $serverUrl…"
        } catch(e:Exception) { status.text="Error: ${e.message}" }
    }

    private fun sendText() {
        val value=input.text.toString().trim()
        if(value.isBlank()) return
        val s=socket
        if(s==null || !s.connected()) { toast("Conecta primero con el servidor");return }
        s.emit("message:send",JSONObject().put("text",value))
        input.setText("")
    }

    private fun chooseFile() {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="*/*" }
        startActivityForResult(i,42)
    }

    @Deprecated("Android compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=42 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        val name=uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        val mime=contentResolver.getType(uri) ?: "application/octet-stream"
        status.text="Subiendo $name…"
        Thread {
            try {
                val temp=File(cacheDir,"upload_${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"),"_")}")
                contentResolver.openInputStream(uri)?.use { src->temp.outputStream().use { dst->src.copyTo(dst) } } ?: throw Exception("No se pudo leer el archivo")
                val body=MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file",name,temp.asRequestBody(mime.toMediaTypeOrNull())).build()
                val req=Request.Builder().url("$serverUrl/api/uploads").post(body).build()
                http.newCall(req).execute().use { response ->
                    if(!response.isSuccessful) throw Exception("Servidor respondió ${response.code}")
                    val result=JSONObject(response.body?.string() ?: "{}")
                    val media=JSONObject().put("name",result.optString("name",name)).put("mimeType",result.optString("mimeType",mime))
                        .put("size",result.optLong("size")).put("downloadPath",result.optString("downloadPath"))
                    runOnUiThread {
                        val s=socket
                        if(s!=null && s.connected()) {
                            s.emit("message:send",JSONObject().put("text","").put("media",media))
                            status.text="Archivo compartido: $name"
                        } else status.text="Archivo subido; conecta a la sala para compartirlo"
                    }
                }
                temp.delete()
            } catch(e:Exception) { runOnUiThread { status.text="Error al subir: ${e.message}" } }
        }.start()
    }

    private fun renderMessage(obj:JSONObject) {
        val who=obj.optString("displayName","Usuario")
        val text=obj.optString("text","")
        val media=obj.optJSONObject("media")
        if(text.isNotBlank()) addBubble("$who: $text",who==displayName)
        if(media!=null) {
            val name=media.optString("name","Archivo")
            val path=media.optString("downloadPath","")
            val type=media.optString("mimeType","")
            val url=if(path.startsWith("http")) path else serverUrl+path
            feed.addView(TextView(this).apply { text="$who · $name";textSize=12f;setTextColor(Color.LTGRAY);setPadding(5,7,5,4) })
            when {
                type.startsWith("image/") -> {
                    val preview=ImageView(this).apply { adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(panel) }
                    Glide.with(this).load(url).into(preview)
                    feed.addView(preview,LinearLayout.LayoutParams(-1,230).apply { setMargins(0,2,0,8) })
                    preview.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(_:Exception) { toast("No se pudo abrir la fotografía") } }
                }
                type.startsWith("video/") -> {
                    val video=VideoView(this)
                    val controller=MediaController(this)
                    controller.setAnchorView(video);video.setMediaController(controller);video.setVideoURI(Uri.parse(url))
                    feed.addView(video,LinearLayout.LayoutParams(-1,230).apply { setMargins(0,2,0,8) })
                    video.setOnErrorListener { _,_,_ -> toast("Este formato de video no es compatible");true }
                }
                type.startsWith("audio/") -> {
                    val play=Button(this).apply { text="▶  Escuchar $name" }
                    feed.addView(play,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,2,0,8) })
                    var current:MediaPlayer?=null
                    play.setOnClickListener {
                        val active=current
                        if(active!=null && active.isPlaying) { active.pause();play.text="▶  Continuar $name" }
                        else if(active!=null) { active.start();play.text="Ⅱ  Pausar $name" }
                        else {
                            play.isEnabled=false;play.text="Cargando audio…"
                            val mp=MediaPlayer();current=mp;mediaPlayers.add(mp)
                            try {
                                mp.setDataSource(url)
                                mp.setOnPreparedListener { player -> player.start();play.isEnabled=true;play.text="Ⅱ  Pausar $name" }
                                mp.setOnCompletionListener { play.text="▶  Volver a escuchar $name" }
                                mp.setOnErrorListener { _,_,_ -> play.isEnabled=true;play.text="▶  Reintentar $name";toast("No se pudo reproducir este audio");true }
                                mp.prepareAsync()
                            } catch(_:Exception) { play.isEnabled=true;play.text="▶  Reintentar $name";toast("No se pudo cargar el audio") }
                        }
                    }
                }
                else -> {
                    val item=Button(this).apply { text="📎  Descargar / abrir $name";setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(_:Exception) { toast("No hay una aplicación compatible") } } }
                    feed.addView(item,LinearLayout.LayoutParams(-1,-2))
                }
            }
        }
    }

    private fun addBubble(value:String,mine:Boolean) {
        val bubble=TextView(this).apply { text=value;textSize=14f;setTextColor(Color.WHITE);setPadding(13,10,13,10);setBackgroundColor(if(mine) Color.rgb(91,62,164) else panel) }
        feed.addView(LinearLayout(this).apply { gravity=if(mine) Gravity.END else Gravity.START;setPadding(0,4,0,4);addView(bubble) })
    }
    private fun toast(s:String) { Toast.makeText(this,s,Toast.LENGTH_LONG).show() }
    override fun onDestroy() { socket?.disconnect();socket?.off();mediaPlayers.forEach { try { it.release() } catch(_:Exception) {} };mediaPlayers.clear();super.onDestroy() }
}
