package com.emaktalk.emakrtcphone.sip

/**
 * Lifecycle of a single call, modelled directly on the states the reference
 * project consumed from Linphone's `Call.State` so the UI layer (which switches
 * on these) ports across unchanged.
 *
 * With Verto + WebRTC there is no SIP state machine handed to us; we derive
 * these from Verto signaling messages (`verto.invite`, `verto.media`,
 * `verto.answer`, `verto.bye`) and the WebRTC [org.webrtc.PeerConnection]
 * connection state. See [SipCoreManager] for the mapping.
 */
enum class CallState {
    Idle,

    // Outgoing
    OutgoingInit,       // we created the offer / sent verto.invite
    OutgoingProgress,   // server accepted the invite, routing
    OutgoingRinging,    // remote party is ringing (early media or 180-equivalent)

    // Incoming
    IncomingReceived,   // verto.invite arrived with a remote offer

    // Established
    Connected,          // signaling answered; media negotiated
    StreamsRunning,     // PeerConnection reports CONNECTED — audio is flowing

    // Teardown
    End,
    Error,
    Released;           // fully torn down; CallUiState becomes null

    val isRinging: Boolean
        get() = this == IncomingReceived ||
            this == OutgoingRinging ||
            this == OutgoingProgress ||
            this == OutgoingInit

    val isConnected: Boolean
        get() = this == Connected || this == StreamsRunning

    val isTerminated: Boolean
        get() = this == End || this == Released || this == Error
}
