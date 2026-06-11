package com.emaktalk.emakrtcphone.sip

import com.emaktalk.emakrtcphone.audio.AudioRoute
import com.emaktalk.emakrtcphone.network.NetworkType

/** Higher-level grouping over [CallState], used to drive UI banners. */
enum class ConnectionPhase {
    /** Setup, ring, or connected with healthy media flow. */
    Healthy,
    /** Network just changed; Verto/WebRTC is re-negotiating media. UI shows a banner. */
    Reconnecting,
    /** No network at all. UI shows "Connection lost". */
    Lost
}

/**
 * Snapshot of the currently active call rendered by the UI.
 * A `null` [CallUiState] means there is no call in progress.
 */
data class CallUiState(
    val state: CallState,
    val remoteUri: String,
    val displayName: String,
    val isOutgoing: Boolean,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.Earpiece,
    val availableRoutes: List<AudioRoute> = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
    val connectionPhase: ConnectionPhase = ConnectionPhase.Healthy,
    val networkType: NetworkType = NetworkType.NONE,
    val quality: CallQualityStats = CallQualityStats.EMPTY
) {
    /** Best human-readable label for the remote party. */
    val title: String get() = displayName.ifBlank { remoteUri }

    val isRinging: Boolean get() = state.isRinging
    val isConnected: Boolean get() = state.isConnected
    val isTerminated: Boolean get() = state.isTerminated
}
