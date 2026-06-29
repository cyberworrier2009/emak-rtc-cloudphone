package com.emaktalk.emakrtcphone.sip

import com.emaktalk.emakrtcphone.audio.AudioRoute
import com.emaktalk.emakrtcphone.network.NetworkType

enum class ConnectionPhase {

    Healthy,

    Reconnecting,

    Lost
}

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
    val quality: CallQualityStats = CallQualityStats.EMPTY,

    val isOnHold: Boolean = false,

    val heldCallTitle: String? = null,

    val isAddingCall: Boolean = false,

    val isTransferring: Boolean = false,

    val canMerge: Boolean = false,

    val isConference: Boolean = false
) {

    val title: String get() = displayName.ifBlank { remoteUri }

    val isRinging: Boolean get() = state.isRinging
    val isConnected: Boolean get() = state.isConnected
    val isTerminated: Boolean get() = state.isTerminated

    val hasSecondCall: Boolean get() = heldCallTitle != null
}
