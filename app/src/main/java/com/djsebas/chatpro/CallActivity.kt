package com.djsebas.chatpro

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class CallActivity : Activity() {
    private var serverUrl = ""
    private var token = ""
    private var callId = ""
    private var mediaType = "audio"
    private var isCaller = false
    private var peerName = "Contacto"
    private var socket: Socket? = null
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var statusView: TextView? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var muted = false
    private var speaker = false
    private var cameraEnabled = true
    private var ending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        serverUrl = intent.getStringExtra("server_url") ?: ""
        token = intent.getStringExtra("token") ?: ""
        callId = intent.getStringExtra("call_id") ?: ""
        mediaType = intent.getStringExtra("media_type") ?: "audio"
        isCaller = intent.getBooleanExtra("is_caller", false)
        peerName = intent.getStringExtra("peer_name") ?: "Contacto"
        if (serverUrl.isBlank() || token.isBlank() || callId.isBlank()) {
            finish()
            return
        }
        showCallUi()
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (mediaType == "video") needed.add(Manifest.permission.CAMERA)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 811)
        } else {
            initializeCall()
        }
    }

    private fun showCallUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(13, 16, 29)) }
        if (mediaType == "video") {
            egl = EglBase.create()
            remoteRenderer = SurfaceViewRenderer(this).apply {
                init(egl!!.eglBaseContext, null)
                setEnableHardwareScaler(true)
            }
            localRenderer = SurfaceViewRenderer(this).apply {
                init(egl!!.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setMirror(true)
            }
            root.addView(remoteRenderer, FrameLayout.LayoutParams(-1, -1))
            val localParams = FrameLayout.LayoutParams(dp(116), dp(164), Gravity.TOP or Gravity.END)
            localParams.setMargins(0, dp(18), dp(14), 0)
            root.addView(localRenderer, localParams)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(22))
            setBackgroundColor(Color.argb(220, 13, 16, 29))
        }
        statusView = TextView(this).apply {
            text = if (isCaller) "Llamando a " + peerName else "Conectando con " + peerName
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(14))
        }
        panel.addView(statusView)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(controlButton("Silenciar") { toggleMute() })
        controls.addView(controlButton("Altavoz") { toggleSpeaker() })
        if (mediaType == "video") {
            controls.addView(controlButton("Cámara") { toggleCamera() })
            controls.addView(controlButton("Girar") { switchCamera() })
        }
        controls.addView(controlButton("Colgar") { endCall() })
        panel.addView(controls)
        val panelParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        root.addView(panel, panelParams)
        setContentView(root)
    }

    private fun controlButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(if (label == "Colgar") Color.rgb(190, 45, 60) else Color.rgb(74, 66, 112))
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setOnClickListener { action() }
        }
    }

    private fun initializeCall() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = mediaType == "video"
            speaker = mediaType == "video"

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
            )
            if (egl == null) egl = EglBase.create()
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
                .createPeerConnectionFactory()

            createPeerConnection()
            connectSignaling()
        } catch (error: Exception) {
            fail("No se pudo preparar la llamada: " + (error.message ?: "error"))
        }
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                runOnUiThread {
                    statusView?.text = when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> "Conectado con " + peerName
                        PeerConnection.IceConnectionState.CHECKING -> "Conectando…"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "Reconectando…"
                        PeerConnection.IceConnectionState.FAILED -> "No se pudo establecer la conexión"
                        else -> statusView?.text
                    } ?: ""
                }
            }
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val data = JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
                socket?.emit("call:ice", JSONObject().put("callId", callId).put("candidate", data))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull()
                if (track != null && remoteRenderer != null) runOnUiThread { track.addSink(remoteRenderer) }
            }
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track() as? VideoTrack
                if (track != null && remoteRenderer != null) runOnUiThread { track.addSink(remoteRenderer) }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() as? VideoTrack
                if (track != null && remoteRenderer != null) runOnUiThread { track.addSink(remoteRenderer) }
            }
        }) ?: throw IllegalStateException("No se pudo crear la conexión WebRTC")

        audioSource = factory?.createAudioSource(MediaConstraints())
        audioTrack = factory?.createAudioTrack("chatpro-audio", audioSource)
        peer?.addTrack(audioTrack, listOf("chatpro-stream"))

        if (mediaType == "video") createLocalVideo()
    }

    private fun createLocalVideo() {
        val enumerator = Camera2Enumerator(this)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No se encontró una cámara disponible")
        capturer = enumerator.createCapturer(cameraName, null) as? CameraVideoCapturer
            ?: throw IllegalStateException("No se pudo abrir la cámara")
        videoSource = factory?.createVideoSource(false)
        textureHelper = SurfaceTextureHelper.create("CHATPRO-Camera", egl!!.eglBaseContext)
        capturer?.initialize(textureHelper, this, videoSource?.capturerObserver)
        capturer?.startCapture(640, 480, 24)
        videoTrack = factory?.createVideoTrack("chatpro-video", videoSource)
        videoTrack?.addSink(localRenderer)
        peer?.addTrack(videoTrack, listOf("chatpro-stream"))
    }

    private fun connectSignaling() {
        val options = IO.Options.builder()
            .setAuth(mapOf("token" to token))
            .setReconnection(true)
            .setTimeout(25000)
            .build()
        socket = IO.socket(serverUrl, options)
        socket?.on(Socket.EVENT_CONNECT) {
            runOnUiThread {
                if (isCaller) {
                    statusView?.text = "Llamando a " + peerName
                } else {
                    statusView?.text = "Aceptando llamada…"
                    socket?.emit("call:accept", JSONObject().put("callId", callId))
                }
            }
        }
        socket?.on("call:accepted") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            if (data.optString("callId") == callId && isCaller) createOffer()
        }
        socket?.on("call:offer") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            if (data.optString("callId") == callId) receiveOffer(data.optJSONObject("sdp"))
        }
        socket?.on("call:answer") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            if (data.optString("callId") == callId) receiveAnswer(data.optJSONObject("sdp"))
        }
        socket?.on("call:ice") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            if (data.optString("callId") == callId) receiveIce(data.optJSONObject("candidate"))
        }
        socket?.on("call:rejected") { runOnUiThread { toast("La llamada fue rechazada"); finish() } }
        socket?.on("call:ended") { args ->
            val data = args.firstOrNull() as? JSONObject ?: JSONObject()
            runOnUiThread {
                if (data.optString("reason") == "no-answer") toast("La persona no respondió")
                else if (data.optString("reason") == "disconnected") toast("Se perdió la conexión")
                finish()
            }
        }
        socket?.on("call:error") { args ->
            val data = args.firstOrNull() as? JSONObject ?: JSONObject()
            runOnUiThread { fail(data.optString("message", "Error de llamada")) }
        }
        socket?.connect()
    }

    private fun createOffer() {
        peer?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) return
                peer?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        socket?.emit("call:offer", JSONObject().put("callId", callId).put(
                            "sdp", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description)
                        ))
                    }
                    override fun onSetFailure(error: String?) { fail("No se pudo preparar la oferta") }
                    override fun onCreateSuccess(description: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { fail("No se pudo iniciar la llamada") }
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    private fun receiveOffer(data: JSONObject?) {
        if (data == null) return
        val description = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(data.optString("type")),
            data.optString("sdp")
        )
        peer?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainIce()
                createAnswer()
            }
            override fun onSetFailure(error: String?) { fail("No se pudo recibir la llamada") }
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, description)
    }

    private fun createAnswer() {
        peer?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) return
                peer?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        socket?.emit("call:answer", JSONObject().put("callId", callId).put(
                            "sdp", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description)
                        ))
                    }
                    override fun onSetFailure(error: String?) { fail("No se pudo responder la llamada") }
                    override fun onCreateSuccess(description: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { fail("No se pudo responder la llamada") }
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    private fun receiveAnswer(data: JSONObject?) {
        if (data == null) return
        val description = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(data.optString("type")),
            data.optString("sdp")
        )
        peer?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainIce()
            }
            override fun onSetFailure(error: String?) { fail("No se pudo completar la llamada") }
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, description)
    }

    private fun receiveIce(data: JSONObject?) {
        if (data == null) return
        val candidate = IceCandidate(
            data.optString("sdpMid"),
            data.optInt("sdpMLineIndex"),
            data.optString("candidate")
        )
        if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else pendingIce.add(candidate)
    }

    private fun drainIce() {
        val queued = pendingIce.toList()
        pendingIce.clear()
        queued.forEach { peer?.addIceCandidate(it) }
    }

    private fun toggleMute() {
        muted = !muted
        audioTrack?.setEnabled(!muted)
        toast(if (muted) "Micrófono silenciado" else "Micrófono activado")
    }

    private fun toggleSpeaker() {
        speaker = !speaker
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.isSpeakerphoneOn = speaker
        toast(if (speaker) "Altavoz activado" else "Altavoz desactivado")
    }

    private fun toggleCamera() {
        cameraEnabled = !cameraEnabled
        videoTrack?.setEnabled(cameraEnabled)
        toast(if (cameraEnabled) "Cámara activada" else "Cámara apagada")
    }

    private fun switchCamera() {
        capturer?.switchCamera(null)
    }

    private fun endCall() {
        if (!ending) socket?.emit("call:end", JSONObject().put("callId", callId))
        ending = true
        finish()
    }

    private fun fail(message: String) {
        runOnUiThread {
            toast(message)
            if (!ending) socket?.emit("call:end", JSONObject().put("callId", callId))
            ending = true
            finish()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 811 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initializeCall()
        } else {
            toast("Se necesita permiso de micrófono y, para video, cámara")
            socket = IO.socket(serverUrl, IO.Options.builder().setAuth(mapOf("token" to token)).build())
            socket?.on(Socket.EVENT_CONNECT) {
                socket?.emit(if (isCaller) "call:end" else "call:reject", JSONObject().put("callId", callId))
                runOnUiThread { finish() }
            }
            socket?.connect()
        }
    }

    private fun releaseWebRtc() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { textureHelper?.dispose() } catch (_: Exception) {}
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioTrack?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { remoteRenderer?.release(); localRenderer?.release() } catch (_: Exception) {}
        try { egl?.release() } catch (_: Exception) {}
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = false
    }

    override fun onDestroy() {
        if (!ending && isFinishing && socket?.connected() == true) {
            socket?.emit("call:end", JSONObject().put("callId", callId))
        }
        ending = true
        socket?.disconnect()
        socket?.off()
        releaseWebRtc()
        super.onDestroy()
    }
}
