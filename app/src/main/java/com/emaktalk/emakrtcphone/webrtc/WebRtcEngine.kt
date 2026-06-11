package com.emaktalk.emakrtcphone.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Process-wide owner of the WebRTC [PeerConnectionFactory] and audio device
 * module. Initialised once from [com.emaktalk.emakrtcphone.sip.SipCoreManager];
 * hands out a fresh [WebRtcSession] per call.
 *
 * This is the media half of what Linphone's `Core` did in the reference
 * project — codecs, RTP, ICE and the mic/speaker device all live here; the
 * signaling half lives in [com.emaktalk.emakrtcphone.verto.VertoClient].
 */
object WebRtcEngine {

    private const val TAG = "WebRtcEngine"

    private lateinit var factory: PeerConnectionFactory
    private var initialized = false

    /**
     * ICE servers. FreeSWITCH (mod_rtc) is ICE-lite and typically reachable on
     * a public address, but the client still benefits from a STUN server to
     * discover its own server-reflexive candidate behind NAT.
     *
     * If calls connect on signaling but then show "Reconnecting" (ICE FAILED),
     * the usual fix is a TURN server: when the phone is behind a symmetric NAT
     * and can't reach FreeSWITCH's candidate directly, TURN relays the media.
     * Fill in [TURN_URL] / [TURN_USER] / [TURN_PASSWORD] to enable it.
     */
    private const val TURN_URL = ""        // e.g. "turn:turn.example.com:3478"
    private const val TURN_USER = ""
    private const val TURN_PASSWORD = ""

    val iceServers: List<PeerConnection.IceServer> = buildList {
        add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        if (TURN_URL.isNotBlank()) {
            add(
                PeerConnection.IceServer.builder(TURN_URL)
                    .setUsername(TURN_USER)
                    .setPassword(TURN_PASSWORD)
                    .createIceServer()
            )
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        // Prefer the device's hardware AEC/NS when present (matches the platform
        // DSP the reference enabled via Linphone). JavaAudioDeviceModule also
        // puts the stream in voice-communication mode for us.
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        initialized = true
        Log.i(TAG, "PeerConnectionFactory ready")
    }

    /** Creates a new per-call session bound to a fresh [PeerConnection]. */
    fun createSession(callId: String, observer: WebRtcSession.Events): WebRtcSession {
        check(initialized) { "WebRtcEngine.initialize() must be called first" }
        return WebRtcSession(callId, factory, iceServers, observer)
    }
}
