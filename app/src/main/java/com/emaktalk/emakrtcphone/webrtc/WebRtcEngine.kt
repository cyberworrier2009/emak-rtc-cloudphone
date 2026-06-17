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

    private val turnApi = TurnServerApi()

    /**
     * STUN-only servers used for the *first* (simple) call attempt. FreeSWITCH
     * (mod_rtc) is ICE-lite and typically reachable on a public address, so on a
     * simple network a STUN server is enough to discover our server-reflexive
     * candidate and connect directly — no TURN relay is allocated.
     *
     * TURN is brought in only as a fallback when this direct path fails; see
     * [fetchTurnIceServers] and the retry logic in
     * [com.emaktalk.emakrtcphone.sip.SipCoreManager].
     */
    val defaultIceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    /**
     * Fetches STUN+TURN servers from the backend for [userId] (fresh Cloudflare
     * credentials each call). Used only on the fallback retry, after a direct
     * connection has failed. Returns [defaultIceServers] if the user id is
     * missing or the backend is unreachable, so the retry can still proceed.
     */
    suspend fun fetchTurnIceServers(
        userId: String?,
        accessToken: String?
    ): List<PeerConnection.IceServer> {
        if (userId.isNullOrBlank()) {
            Log.w(TAG, "No user id for TURN request; falling back to STUN-only")
            return defaultIceServers
        }
        return turnApi.fetchIceServers(userId, accessToken)
            .fold(
                onSuccess = { servers ->
                    if (servers.isNotEmpty()) {
                        Log.i(TAG, "Fetched ${servers.size} ICE server entries from backend for TURN fallback")
                        servers
                    } else {
                        Log.w(TAG, "Backend returned no ICE servers; falling back to STUN-only")
                        defaultIceServers
                    }
                },
                onFailure = {
                    Log.w(TAG, "TURN fetch failed; falling back to STUN-only", it)
                    defaultIceServers
                }
            )
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

    /**
     * Creates a new per-call session bound to a fresh [PeerConnection] using the
     * given [iceServers] — pass [defaultIceServers] for the simple/direct attempt
     * or the result of [fetchTurnIceServers] for the TURN fallback.
     */
    fun createSession(
        callId: String,
        iceServers: List<PeerConnection.IceServer>,
        observer: WebRtcSession.Events
    ): WebRtcSession {
        check(initialized) { "WebRtcEngine.initialize() must be called first" }
        return WebRtcSession(callId, factory, iceServers, observer)
    }
}
