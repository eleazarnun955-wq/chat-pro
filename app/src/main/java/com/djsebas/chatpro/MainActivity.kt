package com.djsebas.chatpro

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private val ink = Color.rgb(13, 16, 29)
    private val panel = Color.rgb(25, 30, 49)
    private val purple = Color.rgb(154, 111, 255)
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
            setPadding(18, 18, 18, 12)
        }
        root.addView(TextView(this).apply {
            text = "CHAT PRO\nComparte tus mezclas terminadas"
            textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(8, 12, 8, 18)
        })
        root.addView(TextView(this).apply {
            text = "  CHATS      GRUPOS      MEZCLAS      PERFIL  "
            textSize = 13f; setTextColor(purple); gravity = Gravity.CENTER
            setPadding(4, 14, 4, 14); setBackgroundColor(panel)
        })
        root.addView(TextView(this).apply {
            text = "Conversación"; textSize = 18f; setTextColor(Color.WHITE); setPadding(4, 18, 4, 10)
        })
        val scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, 4, 4, 12) }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addBubble("¡Bienvenido a CHAT PRO! Comparte mensajes y tus mezclas terminadas de hasta 50 minutos.", false)
        root.addView(TextView(this).apply {
            text = "Música: MP3 · WAV · M4A · FLAC  |  Duración máxima: 50 min"
            textSize = 12f; setTextColor(Color.LTGRAY); setPadding(4, 8, 4, 8)
        })
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        composer.addView(Button(this).apply { text = "+"; setOnClickListener { chooseFile() } })
        input = EditText(this).apply {
            hint = "Escribe un mensaje"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            setSingleLine(true); setPadding(12, 4, 12, 4); setBackgroundColor(panel)
        }
        composer.addView(input, LinearLayout.LayoutParams(0, 52, 1f))
        composer.addView(Button(this).apply { text = "Enviar"; setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) { addBubble(value, true); input.setText("") }
        } })
        root.addView(composer)
        setContentView(root)
    }

    private fun addBubble(text: String, mine: Boolean) {
        val bubble = TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(Color.WHITE)
            setPadding(14, 11, 14, 11); setBackgroundColor(if (mine) Color.rgb(91, 62, 164) else panel)
        }
        messages.addView(LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, 5, 0, 5); addView(bubble)
        })
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/flac", "audio/x-flac"))
        }
        startActivityForResult(intent, 42)
    }

    @Deprecated("Deprecated in Android but supported for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            if (durationMs > 50L * 60L * 1000L) {
                Toast.makeText(this, "El archivo supera el límite de 50 minutos", Toast.LENGTH_LONG).show()
                return
            }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Archivo de música"
            val mins = String.format(Locale.US, "%.1f", durationMs / 60000.0)
            addBubble("🎵 $name\nDuración: $mins min\nArchivo seleccionado (aún no enviado al servidor).", true)
            Toast.makeText(this, "Archivo listo para compartir cuando se conecte el servicio en línea", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo leer el archivo seleccionado", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
