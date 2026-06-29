package com.emaktalk.emakrtcphone.sip

enum class CallState {
    Idle,

    OutgoingInit,
    OutgoingProgress,
    OutgoingRinging,

    IncomingReceived,

    Connected,
    StreamsRunning,

    End,
    Error,
    Released;

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
