package com.hesabat.twopersonmessenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

import org.json.JSONObject

import org.webrtc.SurfaceViewRenderer

class CallActivity : AppCompatActivity() {

    companion object {
        private const val API_BASE =
            "https://hesabat.site/wp/api/"
    }

    private lateinit var room: Room

    private var eventJob: Job? = null

    private var isVideoCall = false
    private var microphoneEnabled = true
    private var cameraEnabled = false
    private var connected = false

    private var callUuid: String = ""

    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null

    private var statusText: TextView? = null

    private var muteButton: Button? = null
    private var cameraButton: Button? = null
    private var endButton: Button? = null

    private val httpClient = OkHttpClient()

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val microphoneGranted =
                permissions[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

            val cameraGranted =
                permissions[Manifest.permission.CAMERA] == true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

            if (!microphoneGranted) {
                Toast.makeText(
                    this,
                    "Mikrofon icazəsi lazımdır",
                    Toast.LENGTH_LONG
                ).show()

                finish()
                return@registerForActivityResult
            }

            if (isVideoCall && !cameraGranted) {
                Toast.makeText(
                    this,
                    "Kamera icazəsi verilmədi. Zəng səsli davam edəcək.",
                    Toast.LENGTH_LONG
                ).show()

                isVideoCall = false
            }

            startLiveKit()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_call)

        callUuid =
            intent.getStringExtra("call_uuid")
                ?: intent.getStringExtra("call_id")
                ?: ""

        isVideoCall =
            intent.getBooleanExtra("is_video", false) ||
                intent.getStringExtra("call_type")
                    ?.equals("video", ignoreCase = true) == true

        statusText =
            findViewByIdSafe("callStatus")

        if (statusText == null) {
            statusText =
                findViewByIdSafe("statusText")
        }

        remoteRenderer =
            findViewByIdSafe("remoteVideo")

        localRenderer =
            findViewByIdSafe("localVideo")

        muteButton =
            findViewByIdSafe("btnMute")

        cameraButton =
            findViewByIdSafe("btnCamera")

        endButton =
            findViewByIdSafe("btnEnd")

        muteButton?.setOnClickListener {
            toggleMicrophone()
        }

        cameraButton?.setOnClickListener {
            toggleCamera()
        }

        endButton?.setOnClickListener {
            endCall()
        }

        if (!isVideoCall) {
            cameraButton?.visibility = View.GONE
            localRenderer?.visibility = View.GONE
        }

        requestRequiredPermissions()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViewByIdSafe(name: String): T? {
        val id = resources.getIdentifier(
            name,
            "id",
            packageName
        )

        if (id == 0) {
            return null
        }

        return findViewById<View>(id) as? T
    }

    private fun requestRequiredPermissions() {

        val permissions = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(
                Manifest.permission.RECORD_AUDIO
            )
        }

        if (
            isVideoCall &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(
                Manifest.permission.CAMERA
            )
        }

        if (permissions.isEmpty()) {
            startLiveKit()
        } else {
            permissionLauncher.launch(
                permissions.toTypedArray()
            )
        }
    }

    private fun startLiveKit() {

        setStatus("Qoşulur...")

        room = LiveKit.create(
            applicationContext
        )

        remoteRenderer?.let {
            room.initVideoRenderer(it)
        }

        localRenderer?.let {
            room.initVideoRenderer(it)
        }

        observeRoomEvents()

        requestLiveKitToken()
    }

    private fun observeRoomEvents() {

        eventJob?.cancel()

        eventJob =
            lifecycleScope.launch {

                room.events.collect { event ->

                    when (event) {

                        is RoomEvent.Connected -> {
                            connected = true
                            setStatus("Zəng qoşuldu")
                        }

                        is RoomEvent.Disconnected -> {
                            connected = false
                            setStatus("Zəng bitdi")

                            if (!isFinishing) {
                                finish()
                            }
                        }

                        is RoomEvent.TrackSubscribed -> {

                            val track = event.track

                            if (track is VideoTrack) {

                                remoteRenderer?.let { renderer ->

                                    track.addRenderer(
                                        renderer
                                    )

                                    renderer.visibility =
                                        View.VISIBLE
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
    }

    private fun requestLiveKitToken() {

        lifecycleScope.launch {

            try {

                val sessionToken =
                    Session.token(this@CallActivity)

                if (sessionToken.isBlank()) {
                    showError(
                        "Login token tapılmadı"
                    )
                    return@launch
                }

                val formBuilder =
                    FormBody.Builder()

                if (callUuid.isNotBlank()) {
                    formBuilder.add(
                        "call_uuid",
                        callUuid
                    )
                }

                formBuilder.add(
                    "call_type",
                    if (isVideoCall) {
                        "video"
                    } else {
                        "audio"
                    }
                )

                val request =
                    Request.Builder()
                        .url(
                            API_BASE +
                                "livekit_token.php"
                        )
                        .addHeader(
                            "Authorization",
                            "Bearer $sessionToken"
                        )
                        .post(
                            formBuilder.build()
                        )
                        .build()

                val response =
                    kotlinx.coroutines
                        .withContext(
                            kotlinx.coroutines
                                .Dispatchers.IO
                        ) {
                            httpClient
                                .newCall(request)
                                .execute()
                        }

                val body =
                    response.body?.string()
                        ?: ""

                if (!response.isSuccessful) {

                    showError(
                        "Server xətası: " +
                            response.code
                    )

                    return@launch
                }

                val json =
                    JSONObject(body)

                if (!json.optBoolean("ok")) {

                    showError(
                        json.optString(
                            "error",
                            "LiveKit token alınmadı"
                        )
                    )

                    return@launch
                }

                val liveKitUrl =
                    when {
                        json.has("url") ->
                            json.optString("url")

                        json.has("server_url") ->
                            json.optString(
                                "server_url"
                            )

                        else -> ""
                    }

                val liveKitToken =
                    when {
                        json.has("token") ->
                            json.optString("token")

                        json.has(
                            "participant_token"
                        ) ->
                            json.optString(
                                "participant_token"
                            )

                        else -> ""
                    }

                if (
                    liveKitUrl.isBlank() ||
                    liveKitToken.isBlank()
                ) {

                    showError(
                        "Server LiveKit URL və ya token qaytarmadı"
                    )

                    return@launch
                }

                connectToRoom(
                    liveKitUrl,
                    liveKitToken
                )

            } catch (e: Exception) {

                showError(
                    e.message
                        ?: "LiveKit token xətası"
                )
            }
        }
    }

    private suspend fun connectToRoom(
        url: String,
        token: String
    ) {

        try {

            setStatus("LiveKit-ə qoşulur...")

            room.connect(
                url,
                token
            )

            connected = true

            room.localParticipant
                .setMicrophoneEnabled(true)

            microphoneEnabled = true

            if (isVideoCall) {

                room.localParticipant
                    .setCameraEnabled(true)

                cameraEnabled = true

                attachLocalVideo()
            }

            setStatus("Zəng qoşuldu")

        } catch (e: Exception) {

            showError(
                "Zəng bağlantısı alınmadı: " +
                    (
                        e.message
                            ?: "naməlum xəta"
                    )
            )
        }
    }

    private fun attachLocalVideo() {

        val renderer =
            localRenderer
                ?: return

        renderer.visibility =
            View.VISIBLE

        val publications =
            room.localParticipant
                .trackPublications
                .values

        for (publication in publications) {

            val track =
                publication.track

            if (track is VideoTrack) {

                track.addRenderer(
                    renderer
                )

                break
            }
        }
    }

    private fun toggleMicrophone() {

        if (!::room.isInitialized) {
            return
        }

        lifecycleScope.launch {

            try {

                microphoneEnabled =
                    !microphoneEnabled

                room.localParticipant
                    .setMicrophoneEnabled(
                        microphoneEnabled
                    )

                muteButton?.text =
                    if (microphoneEnabled) {
                        "Səsi söndür"
                    } else {
                        "Səsi aç"
                    }

            } catch (e: Exception) {

                Toast.makeText(
                    this@CallActivity,
                    "Mikrofon dəyişdirilə bilmədi",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun toggleCamera() {

        if (!::room.isInitialized) {
            return
        }

        lifecycleScope.launch {

            try {

                cameraEnabled =
                    !cameraEnabled

                room.localParticipant
                    .setCameraEnabled(
                        cameraEnabled
                    )

                if (cameraEnabled) {
                    attachLocalVideo()
                } else {
                    localRenderer?.visibility =
                        View.GONE
                }

                cameraButton?.text =
                    if (cameraEnabled) {
                        "Kameranı söndür"
                    } else {
                        "Kameranı aç"
                    }

            } catch (e: Exception) {

                Toast.makeText(
                    this@CallActivity,
                    "Kamera dəyişdirilə bilmədi",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setStatus(
        text: String
    ) {

        runOnUiThread {
            statusText?.text = text
        }
    }

    private fun showError(
        message: String
    ) {

        runOnUiThread {

            setStatus("Xəta")

            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun endCall() {

        connected = false

        if (::room.isInitialized) {

            try {
                room.disconnect()
            } catch (_: Exception) {
            }
        }

        finish()
    }

    override fun onDestroy() {

        eventJob?.cancel()

        if (::room.isInitialized) {

            try {
                room.disconnect()
            } catch (_: Exception) {
            }

            try {
                room.release()
            } catch (_: Exception) {
            }
        }

        super.onDestroy()
    }
}
