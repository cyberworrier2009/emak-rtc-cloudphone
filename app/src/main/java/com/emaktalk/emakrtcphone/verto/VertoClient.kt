package com.emaktalk.emakrtcphone.verto

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * FreeSWITCH **Verto** (`mod_verto`) signaling client.
 *
 * Verto is a thin JSON-RPC 2.0 protocol carried over a (usually secure)
 * WebSocket. It is FreeSWITCH's native WebRTC signaling and the counterpart to
 * what the reference project did with Linphone's SIP stack:
 *
 *  - [login]              ≈ SIP REGISTER (authenticates the session)
 *  - [invite]             ≈ outgoing INVITE (carries our WebRTC SDP offer)
 *  - server `verto.media` ≈ early media / 183 (remote SDP answer for ringback)
 *  - server `verto.answer`≈ 200 OK (remote SDP answer, call connected)
 *  - [answer]             ≈ our 200 OK for an incoming call (our SDP answer)
 *  - [bye]                ≈ BYE / CANCEL
 *  - [info]               ≈ SIP INFO (used here to carry DTMF digits)
 *
 * The actual audio never touches this class — it rides the WebRTC
 * [org.webrtc.PeerConnection] negotiated with the SDP exchanged here. This
 * object only moves JSON.
 *
 * All [Listener] callbacks are delivered on the main thread.
 */
class VertoClient {

    /** Events surfaced to [com.emaktalk.emakrtcphone.sip.SipCoreManager]. */
    interface Listener {
        fun onConnecting()
        fun onLoginSuccess()
        fun onLoginFailed(reason: String)
        fun onDisconnected(reason: String)

        /** Incoming call: remote SDP offer arrived in a `verto.invite`. */
        fun onIncomingCall(callId: String, callerName: String, callerNumber: String, sdpOffer: String)

        /** Early media for our outgoing call (ringback). Remote SDP answer. */
        fun onMedia(callId: String, sdpAnswer: String)

        /** Our outgoing call was answered. Remote SDP answer (may repeat verto.media's). */
        fun onCallAnswered(callId: String, sdpAnswer: String?)

        /** Either side hung up. */
        fun onBye(callId: String, cause: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestId = AtomicInteger(1)

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null

    // Verto session identity. Generated once per login and echoed on every
    // request so FreeSWITCH can correlate the WebSocket with the session.
    private var sessionId: String = UUID.randomUUID().toString()
    private var login: String = ""
    private var passwd: String = ""

    @Volatile private var loggedIn = false
    @Volatile private var intentionalClose = false

    // Pending JSON-RPC requests we sent and are awaiting a result/error for,
    // keyed by request id. Currently only the login request needs follow-up.
    private val pending = mutableMapOf<Int, (JSONObject?, JSONObject?) -> Unit>()

    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    val isLoggedIn: Boolean get() = loggedIn

    /**
     * Opens the WebSocket to [wsUrl] (e.g. `wss://fs.example.com:8082`) and, once
     * connected, sends the Verto `login`. [loginId] is typically `user@domain`.
     */
    fun connect(wsUrl: String, loginId: String, password: String, listener: Listener) {
        disconnect("reconnecting")
        this.listener = listener
        this.login = loginId
        this.passwd = password
        this.sessionId = UUID.randomUUID().toString()
        this.loggedIn = false
        this.intentionalClose = false

        notify { it.onConnecting() }
        Log.i(TAG, "Connecting Verto WebSocket -> $wsUrl as $loginId")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, socketListener)
    }

    fun disconnect(reason: String = "bye") {
        intentionalClose = true
        loggedIn = false
        pending.clear()
        webSocket?.close(1000, reason)
        webSocket = null
    }

    // region Outgoing requests ------------------------------------------------

    /** Sends our WebRTC offer as a `verto.invite`. */
    fun invite(callId: String, destination: String, callerIdNumber: String, sdpOffer: String) {
        // dialogParams mirror what verto.js sends for an outbound call. The
        // bandwidth/useMic/useSpeak fields are part of the documented mod_verto
        // dialog and some FreeSWITCH builds expect them to be present.
        val dialogParams = JSONObject().apply {
            put("callID", callId)
            put("destination_number", destination)
            put("caller_id_name", callerIdNumber)
            put("caller_id_number", callerIdNumber)
            put("remote_caller_id_name", "Outbound Call")
            put("remote_caller_id_number", destination)
            put("outgoingBandwidth", "default")
            put("incomingBandwidth", "default")
            put("useVideo", false)
            put("useStereo", false)
            put("useMic", "any")
            put("useSpeak", "any")
            put("useCamera", "any")
            put("screenShare", false)
            put("dedEnc", false)
            put("mirrorInput", false)
        }
        val params = JSONObject().apply {
            put("sdp", sdpOffer)
            put("dialogParams", dialogParams)
        }
        sendRequest("verto.invite", params)
    }

    /** Answers an incoming call with our WebRTC SDP answer. */
    fun answer(callId: String, sdpAnswer: String) {
        val dialogParams = JSONObject().apply { put("callID", callId) }
        val params = JSONObject().apply {
            put("sdp", sdpAnswer)
            put("dialogParams", dialogParams)
        }
        sendRequest("verto.answer", params)
    }

    /** Hangs up / cancels a call. */
    fun bye(callId: String, cause: String = "NORMAL_CLEARING") {
        val dialogParams = JSONObject().apply { put("callID", callId) }
        val params = JSONObject().apply {
            put("dialogParams", dialogParams)
            put("cause", cause)
        }
        sendRequest("verto.bye", params)
    }

    /**
     * Holds or resumes a call via `verto.modify`. [action] is "hold", "unhold"
     * or "toggleHold" — the same actions verto.js issues. FreeSWITCH pauses/
     * resumes the media for this leg (so a held party hears hold music / silence
     * and stops receiving our audio) without tearing the call down.
     */
    fun modify(callId: String, action: String) {
        val dialogParams = JSONObject().apply { put("callID", callId) }
        val params = JSONObject().apply {
            put("action", action)
            put("dialogParams", dialogParams)
        }
        sendRequest("verto.modify", params)
    }

    /**
     * Blind-transfers a call to [destination] via `verto.modify` action
     * "transfer". Logs the FreeSWITCH response so we can tell whether this build
     * supports verto transfer (some only handle hold/unhold/toggleHold).
     */
    fun transfer(callId: String, destination: String) {
        val dialogParams = JSONObject().apply { put("callID", callId) }
        val params = JSONObject().apply {
            put("action", "transfer")
            put("destination", destination)
            put("dialogParams", dialogParams)
        }
        sendRequest("verto.modify", params) { result, error ->
            if (error != null) Log.w(TAG, "verto.modify transfer ERROR: $error")
            else Log.i(TAG, "verto.modify transfer OK: $result")
        }
    }

    /** Sends a DTMF digit out-of-band (carried as a `verto.info`). */
    fun sendDtmf(callId: String, digit: Char) {
        val dialogParams = JSONObject().apply { put("callID", callId) }
        val params = JSONObject().apply {
            put("dtmf", digit.toString())
            put("dialogParams", dialogParams)
        }
        sendRequest("verto.info", params)
    }

    // endregion

    private fun sendLogin() {
        val params = JSONObject().apply {
            put("login", login)
            put("passwd", passwd)
            put("sessid", sessionId)
            put("loginParams", JSONObject())
            put("userVariables", JSONObject())
        }
        sendRequest("login", params) { result, error ->
            if (error != null) {
                val msg = error.optString("message", "Login failed")
                Log.w(TAG, "Verto login failed: $msg")
                loggedIn = false
                notify { it.onLoginFailed(msg) }
            } else {
                Log.i(TAG, "Verto login OK: ${result?.optString("message")}")
                loggedIn = true
                notify { it.onLoginSuccess() }
            }
        }
    }

    private fun sendRequest(
        method: String,
        params: JSONObject,
        onResult: ((result: JSONObject?, error: JSONObject?) -> Unit)? = null
    ) {
        val id = requestId.getAndIncrement()
        if (onResult != null) pending[id] = onResult
        // verto.js's JsonRpcClient stamps the sessid onto the params of EVERY
        // request (not just login). FreeSWITCH uses it to bind the call to this
        // authenticated WebSocket session — without it the media leg never gets
        // associated, which manifests as a connected call with no audio.
        if (!params.has("sessid")) params.put("sessid", sessionId)
        val msg = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val text = msg.toString()
        Log.d(TAG, "-> $method (id=$id)")
        val ok = webSocket?.send(text) ?: false
        if (!ok) {
            pending.remove(id)
            Log.w(TAG, "Failed to send $method — socket not open")
        }
    }

    /** Acknowledge a server-initiated request so FreeSWITCH stops retrying it. */
    private fun sendResultAck(id: Int, method: String) {
        val msg = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", JSONObject().put("method", method))
        }
        webSocket?.send(msg.toString())
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open; sending login")
            sendLogin()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleMessage(JSONObject(text)) }
                .onFailure { Log.w(TAG, "Bad Verto message: $text", it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed ($code $reason)")
            loggedIn = false
            if (!intentionalClose) notify { it.onDisconnected(reason.ifBlank { "closed" }) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure", t)
            loggedIn = false
            if (!intentionalClose) notify { it.onDisconnected(t.message ?: "connection failed") }
        }
    }

    private fun handleMessage(json: JSONObject) {
        // Response to one of our requests (login, invite, ...).
        if (json.has("id") && (json.has("result") || json.has("error"))) {
            val id = json.optInt("id", -1)
            val cb = pending.remove(id)
            if (cb != null) {
                val result = json.optJSONObject("result")
                val error = json.optJSONObject("error")
                mainHandler.post { cb(result, error) }
            }
            return
        }

        // Server-initiated request (verto.media, verto.answer, verto.bye, ...).
        val method = json.optString("method", "")
        if (method.isEmpty()) return
        val id = if (json.has("id")) json.optInt("id") else null
        val params = json.optJSONObject("params") ?: JSONObject()

        when (method) {
            "verto.invite" -> {
                val callId = params.optString("callID")
                val sdp = params.optString("sdp")
                val callerName = params.optString("caller_id_name")
                val callerNumber = params.optString("caller_id_number")
                id?.let { sendResultAck(it, method) }
                notify { it.onIncomingCall(callId, callerName, callerNumber, sdp) }
            }
            "verto.media" -> {
                val callId = params.optString("callID")
                val sdp = params.optString("sdp")
                id?.let { sendResultAck(it, method) }
                notify { it.onMedia(callId, sdp) }
            }
            "verto.answer" -> {
                val callId = params.optString("callID")
                val sdp = if (params.has("sdp")) params.optString("sdp") else null
                id?.let { sendResultAck(it, method) }
                notify { it.onCallAnswered(callId, sdp) }
            }
            "verto.bye" -> {
                val callId = params.optString("callID")
                val cause = params.optString("cause", "NORMAL_CLEARING")
                id?.let { sendResultAck(it, method) }
                notify { it.onBye(callId, cause) }
            }
            "verto.ping" -> {
                // Keepalive: echo back so FreeSWITCH considers the session live.
                id?.let { sendResultAck(it, "verto.pong") }
            }
            else -> {
                Log.d(TAG, "Unhandled Verto method: $method")
                id?.let { sendResultAck(it, method) }
            }
        }
    }

    private inline fun notify(crossinline block: (Listener) -> Unit) {
        val l = listener ?: return
        mainHandler.post { block(l) }
    }

    private fun buildHttpClient(): OkHttpClient {
        // NOTE: do NOT set pingInterval(). mod_verto does not reply to protocol-
        // level WebSocket PING frames, so OkHttp's keep-alive ping times out
        // ("0 successful ping/pongs") and tears the socket down every interval —
        // which also looked like the session flapping to "Connecting" mid-call.
        // Keep-alive is handled at the application layer instead: mod_verto sends
        // its own "verto.ping", which we answer in handleMessage() (matching how
        // verto.js stays connected).
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)

        // DEV AFFORDANCE: FreeSWITCH installs usually ship a self-signed TLS
        // cert on the verto WSS port, which a stock TrustManager rejects. When
        // [ALLOW_INSECURE_TLS] is true we trust any cert so a lab server "just
        // works". TURN THIS OFF for production and pin/install the real CA.
        if (ALLOW_INSECURE_TLS) {
            runCatching {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
                val ssl = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
                }
                builder.sslSocketFactory(ssl.socketFactory, trustAll)
                builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
            }.onFailure { Log.w(TAG, "Could not install permissive TLS", it) }
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "VertoClient"

        /**
         * Trust self-signed TLS certs on the verto WSS port. Convenient for a
         * lab FreeSWITCH; **set to false in production**.
         */
        const val ALLOW_INSECURE_TLS = true
    }
}
