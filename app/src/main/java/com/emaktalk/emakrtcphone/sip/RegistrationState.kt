package com.emaktalk.emakrtcphone.sip

enum class RegistrationState {
    None,
    Progress,
    Ok,
    Cleared,
    Failed
}

enum class VertoTransport(val scheme: String, val defaultPort: Int) {
    WS("ws", 5066),
    WSS("wss", 7443)
}
