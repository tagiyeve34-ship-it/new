package com.hesabat.twopersonmessenger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.hesabat.twopersonmessenger.databinding.ActivityCallBinding
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {
    private lateinit var b: ActivityCallBinding
    private val h = Handler(Looper.getMainLooper())
    private var room: Room? = null
    private var eventJob: Job? = null
    private var callUuid = ""
    private var incoming = false
    private var video = false
    private var muted = false
    private var cameraOn = true
    private var lastSignal = 0L
    private var connecting = false
    private val poll = object : Runnable { override fun run() { pollControlSignals(); h.postDelayed(this, 900) } }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) prepareCall() else {
            Toast.makeText(this, "Mikrofon/kamera icazəsi lazımdır", Toast.LENGTH_LONG).show(); finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCallBinding.inflate(layoutInflater); setContentView(b.root)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        incoming = intent.getBooleanExtra("incoming", false)
        video = intent.getBooleanExtra("video", false)
        callUuid = intent.getStringExtra("call_uuid") ?: ""
        b.callType.text = if (video) "Video zəng" else "Səsli zəng"
        b.videoContainer.visibility = if (video) View.VISIBLE else View.GONE
        b.localVideo.visibility = View.GONE // first stable LiveKit build: remote video only
        b.cameraBtn.visibility = if (video) View.VISIBLE else View.GONE
        b.switchBtn.visibility = View.GONE
        b.acceptBtn.visibility = if (incoming) View.VISIBLE else View.GONE
        b.rejectBtn.visibility = if (incoming) View.VISIBLE else View.GONE
        b.controls.visibility = if (incoming) View.GONE else View.VISIBLE

        b.acceptBtn.setOnClickListener {
            b.acceptBtn.visibility = View.GONE; b.rejectBtn.visibility = View.GONE; b.controls.visibility = View.VISIBLE
            signal("answer"); ensurePermissions()
        }
        b.rejectBtn.setOnClickListener { signal("reject"); finish() }
        b.endBtn.setOnClickListener { signal("hangup"); finish() }
        b.micBtn.setOnClickListener {
            muted = !muted
            lifecycleScope.launch { room?.localParticipant?.setMicrophoneEnabled(!muted) }
            b.micBtn.text = if (muted) "Mikrofon aç" else "Mikrofon"
        }
        b.cameraBtn.setOnClickListener {
            cameraOn = !cameraOn
            lifecycleScope.launch { room?.localParticipant?.setCameraEnabled(cameraOn) }
            b.cameraBtn.text = if (cameraOn) "Kamera" else "Kamera aç"
        }
        b.speakerBtn.setOnClickListener {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            run { am.isSpeakerphoneOn = !am.isSpeakerphoneOn; b.speakerBtn.text = if (am.isSpeakerphoneOn) "Səs: açıq" else "Səs" }
        }
        if (!incoming) ensurePermissions() else b.callStatus.text = "Gələn zəng"
        h.post(poll)
    }

    private fun ensurePermissions() {
        val ps = mutableListOf(Manifest.permission.RECORD_AUDIO); if (video) ps += Manifest.permission.CAMERA
        val missing = ps.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) prepareCall() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun prepareCall() {
        if (connecting) return
        connecting = true
        if (!incoming && callUuid.isBlank()) {
            b.callStatus.text = "Zəng hazırlanır…"
            Api.post("call_start.php", mapOf("call_type" to if (video) "video" else "audio"), Session.token(this)) { ok, raw ->
                val r = if (ok) runCatching { Gson().fromJson(raw, CallStartResponse::class.java) }.getOrNull() else null
                if (r == null || r.call_uuid.isBlank()) runOnUiThread { fail("Zəng başladıla bilmədi") }
                else { callUuid = r.call_uuid; requestLiveKitToken() }
            }
        } else requestLiveKitToken()
    }

    private fun requestLiveKitToken() {
        Api.post("livekit_token.php", mapOf("call_uuid" to callUuid), Session.token(this)) { ok, raw ->
            val r = if (ok) runCatching { Gson().fromJson(raw, LiveKitTokenResponse::class.java) }.getOrNull() else null
            if (r == null || !r.ok || r.server_url.isBlank() || r.participant_token.isBlank()) runOnUiThread { fail("Zəng serverinə qoşulmaq mümkün olmadı") }
            else runOnUiThread { connectLiveKit(r.server_url, r.participant_token) }
        }
    }

    private fun connectLiveKit(url: String, token: String) {
        val lkRoom = LiveKit.create(applicationContext)
        room = lkRoom
        if (video) lkRoom.initVideoRenderer(b.remoteVideo)
        eventJob = lifecycleScope.launch {
            launch {
                lkRoom.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> if (event.track is VideoTrack) {
                            (event.track as VideoTrack).addRenderer(b.remoteVideo)
                        }
                        is RoomEvent.Disconnected -> runOnUiThread { b.callStatus.text = "Zəng bitdi" }
                        else -> Unit
                    }
                }
            }
            try {
                b.callStatus.text = "Qoşulur…"
                lkRoom.connect(url, token)
                lkRoom.localParticipant.setMicrophoneEnabled(true)
                if (video) lkRoom.localParticipant.setCameraEnabled(true)
                b.callStatus.text = "Qoşuldu"
            } catch (e: Throwable) { fail("LiveKit bağlantısı alınmadı") }
        }
    }

    private fun signal(type: String) {
        if (callUuid.isBlank()) return
        Api.post("call_signal.php", mapOf("call_uuid" to callUuid, "signal_type" to type, "payload" to null), Session.token(this)) { _, _ -> }
    }

    private fun pollControlSignals() {
        if (callUuid.isBlank()) return
        Api.get("call_poll.php?after_id=$lastSignal", Session.token(this)) { ok, raw ->
            if (!ok) return@get
            val r = runCatching { Gson().fromJson(raw, SignalResponse::class.java) }.getOrNull() ?: return@get
            r.signals.filter { it.call_uuid == callUuid }.forEach { s ->
                lastSignal = maxOf(lastSignal, s.id)
                if (s.signal_type == "hangup" || s.signal_type == "reject") runOnUiThread {
                    b.callStatus.text = if (s.signal_type == "reject") "Zəng rədd edildi" else "Zəng bitdi"
                    h.postDelayed({ finish() }, 500)
                }
            }
        }
    }

    private fun fail(message: String) { b.callStatus.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show(); h.postDelayed({ finish() }, 1600) }

    override fun onDestroy() {
        h.removeCallbacks(poll); eventJob?.cancel()
        room?.disconnect(); room?.release(); room = null
        super.onDestroy()
    }
}
