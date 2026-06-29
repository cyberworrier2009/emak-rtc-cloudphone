package com.emaktalk.emakrtcphone.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

object WebRtcEngine {

    private const val TAG = "WebRtcEngine"

    private lateinit var factory: PeerConnectionFactory
    private var initialized = false

    private val turnApi = TurnServerApi()

    val defaultIceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

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

    fun createSession(
        callId: String,
        iceServers: List<PeerConnection.IceServer>,
        observer: WebRtcSession.Events,
        forceRelay: Boolean = false
    ): WebRtcSession {
        check(initialized) { "WebRtcEngine.initialize() must be called first" }
        return WebRtcSession(callId, factory, iceServers, observer, forceRelay)
    }
}
