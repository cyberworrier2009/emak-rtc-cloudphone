package com.emaktalk.emakrtcphone.sip

/**
 * Account/login state, mirroring the subset of Linphone's `RegistrationState`
 * the reference UI relied on. For Verto, "registration" is the `login` exchange
 * over the WebSocket: [Progress] while the socket connects + login is in flight,
 * [Ok] once FreeSWITCH replies "logged in", [Failed] on an auth error or a
 * dropped socket, [Cleared] after an explicit sign-out.
 */
enum class RegistrationState {
    None,
    Progress,
    Ok,
    Cleared,
    Failed
}

/**
 * How the Verto WebSocket connects to FreeSWITCH. Replaces Linphone's
 * UDP/TCP/TLS `TransportType`: mod_verto only speaks WebSocket, either plain
 * (`ws://`, default port 8081) or TLS (`wss://`, default port 8082).
 */
enum class VertoTransport(val scheme: String, val defaultPort: Int) {
    WS("ws", 8081),
    WSS("wss", 8082)
}
