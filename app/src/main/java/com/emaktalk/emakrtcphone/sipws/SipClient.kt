package com.emaktalk.emakrtcphone.sipws

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SipClient {

    interface Listener {
        fun onConnecting()
        fun onLoginSuccess()
        fun onLoginFailed(reason: String)
        fun onDisconnected(reason: String)

        fun onIncomingCall(callId: String, callerName: String, callerNumber: String, sdpOffer: String)

        fun onRinging(callId: String)

        fun onMedia(callId: String, sdpAnswer: String)

        fun onCallAnswered(callId: String, sdpAnswer: String?)

        fun onBye(callId: String, cause: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cseqCounter = AtomicInteger(1)

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null

    private var username: String = ""
    private var domain: String = ""
    private var password: String = ""
    private var fromUri: String = ""

    private val instanceId: String = UUID.randomUUID().toString().replace("-", "").take(12)
    private val contactUri: String get() = "sip:$username@$instanceId.invalid;transport=ws"
    private var viaTransport = "WSS"

    @Volatile private var registered = false
    @Volatile private var intentionalClose = false

    private var regCallId = newCallId()
    private val regFromTag = newTag()
    private var regAuth: String? = null
    private var authTriedRegister = false
    private val nc = AtomicInteger(0)

    private val pending = mutableMapOf<String, (SipMessage) -> Unit>()

    private val dialogs = mutableMapOf<String, Dialog>()

    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    val isLoggedIn: Boolean get() = registered

    private class Dialog(
        val callId: String,
        val localTag: String,
        val isOutgoing: Boolean
    ) {
        var remoteTag: String? = null
        var fromUri: String = ""
        var fromDisplay: String = ""
        var toUri: String = ""
        var remoteTarget: String? = null
        var inviteCseq: Int = 1
        var inviteBranch: String = ""
        var routeSet: List<String> = emptyList()
        var localSdp: String? = null
        var established = false
        var terminated = false
        var inviteAuthTried = false

        var incomingInvite: SipMessage? = null
    }

    fun connect(wsUrl: String, loginId: String, password: String, listener: Listener) {
        disconnect("reconnecting")
        this.listener = listener
        this.username = loginId.substringBefore("@").trim()

        this.domain = loginId.substringAfter("@", loginId).trim()
        this.password = password
        this.fromUri = "sip:$username@$domain"
        this.viaTransport = if (wsUrl.startsWith("ws://")) "WS" else "WSS"
        this.registered = false
        this.intentionalClose = false
        this.regCallId = newCallId()
        this.regAuth = null
        this.authTriedRegister = false

        notify { it.onConnecting() }
        Log.i(TAG, "Connecting SIP WebSocket -> $wsUrl as $loginId")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Sec-WebSocket-Protocol", "sip")
            .build()
        webSocket = httpClient.newWebSocket(request, socketListener)
    }

    fun disconnect(reason: String = "bye") {
        intentionalClose = true
        registered = false

        if (webSocket != null && username.isNotBlank()) {
            runCatching { sendRegister(expires = 0) }
        }
        pending.clear()
        dialogs.clear()
        cancelKeepalive()
        cancelReRegister()
        webSocket?.close(1000, reason)
        webSocket = null
    }

    fun invite(callId: String, destination: String, callerIdNumber: String, sdpOffer: String) {
        val dialog = Dialog(callId, newTag(), isOutgoing = true).apply {
            fromUri = this@SipClient.fromUri
            fromDisplay = callerIdNumber
            toUri = "sip:$destination@$domain"
            inviteCseq = cseqCounter.getAndIncrement()
            localSdp = sdpOffer
        }
        dialogs[callId] = dialog
        sendInvite(dialog, sdpOffer, auth = null)
    }

    fun answer(callId: String, sdpAnswer: String) {
        val dialog = dialogs[callId] ?: run {
            Log.w(TAG, "answer: no dialog for $callId"); return
        }
        val invite = dialog.incomingInvite ?: run {
            Log.w(TAG, "answer: no pending INVITE for $callId"); return
        }
        dialog.localSdp = sdpAnswer

        val headers = ArrayList<Pair<String, String>>()
        invite.headersOf("Via").forEach { headers.add("Via" to it) }
        headers.add("From" to (invite.header("From") ?: ""))
        headers.add("To" to withTag(invite.header("To") ?: "", dialog.localTag))
        headers.add("Call-ID" to callId)
        headers.add("CSeq" to (invite.header("CSeq") ?: "1 INVITE"))
        headers.add("Contact" to "<$contactUri>")
        headers.add("Content-Type" to "application/sdp")
        sendMessage(SipMessage.response(200, "OK", headers, sdpAnswer))
        dialog.established = true
    }

    fun bye(callId: String, cause: String = "NORMAL_CLEARING") {
        val dialog = dialogs[callId] ?: return
        when {

            !dialog.isOutgoing && !dialog.established -> rejectIncoming(dialog, cause)

            dialog.isOutgoing && !dialog.established -> sendCancel(dialog)

            else -> sendBye(dialog, cause)
        }
        if (!dialog.established) removeDialog(callId)
    }

    fun modify(callId: String, action: String) {
        val dialog = dialogs[callId] ?: return
        if (!dialog.established) return
        val base = dialog.localSdp ?: return
        val hold = action != "unhold"
        val munged = setSdpDirection(base, if (hold) "sendonly" else "sendrecv")
        dialog.localSdp = munged
        sendReInvite(dialog, munged)
    }

    fun transfer(callId: String, destination: String) {
        val dialog = dialogs[callId] ?: return
        if (!dialog.established) {
            Log.w(TAG, "transfer: leg $callId not established; REFER would fail")
            return
        }
        val branch = newBranch()
        val target = dialog.remoteTarget ?: dialog.toUri
        val headers = inDialogHeaders(dialog, "REFER", branch)
        headers.add("Refer-To" to "<sip:$destination@$domain>")
        headers.add("Referred-By" to "<$fromUri>")
        headers.add("Contact" to "<$contactUri>")
        pending[branch] = { resp ->
            if (resp.statusCode in 200..299) Log.i(TAG, "REFER accepted (${resp.statusCode})")
            else Log.w(TAG, "REFER failed (${resp.statusCode} ${resp.reason})")
        }
        sendMessage(SipMessage.request("REFER", target, headers))
    }

    fun sendDtmf(callId: String, digit: Char) {
        val dialog = dialogs[callId] ?: return
        if (!dialog.established) return
        val branch = newBranch()
        val target = dialog.remoteTarget ?: dialog.toUri
        val headers = inDialogHeaders(dialog, "INFO", branch)
        headers.add("Content-Type" to "application/dtmf-relay")
        val body = "Signal=$digit\r\nDuration=160\r\n"
        sendMessage(SipMessage.request("INFO", target, headers, body))
    }

    private fun sendRegister(expires: Int = REGISTER_EXPIRES, auth: String? = regAuth) {
        val branch = newBranch()
        val cseq = cseqCounter.getAndIncrement()
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Via" to "SIP/2.0/$viaTransport $instanceId.invalid;branch=$branch;rport")
        headers.add("Max-Forwards" to "70")
        headers.add("From" to "<$fromUri>;tag=$regFromTag")
        headers.add("To" to "<$fromUri>")
        headers.add("Call-ID" to regCallId)
        headers.add("CSeq" to "$cseq REGISTER")
        headers.add("Contact" to "<$contactUri>;expires=$expires")
        headers.add("Expires" to "$expires")
        headers.add("Supported" to "path, gruu")
        headers.add("User-Agent" to USER_AGENT)
        auth?.let { headers.add("Authorization" to it) }
        if (expires > 0) {
            pending[branch] = { resp -> onRegisterResponse(resp) }
        }
        sendMessage(SipMessage.request("REGISTER", "sip:$domain", headers))
    }

    private fun onRegisterResponse(resp: SipMessage) {
        when (resp.statusCode) {
            401, 407 -> {
                if (authTriedRegister) {
                    registered = false
                    notify { it.onLoginFailed("Authentication rejected") }
                    return
                }
                val challenge = resp.header("WWW-Authenticate")?.let { DigestAuth.parseChallenge(it) }
                    ?: resp.header("Proxy-Authenticate")?.let { DigestAuth.parseChallenge(it) }
                if (challenge == null) {
                    notify { it.onLoginFailed("Malformed auth challenge") }
                    return
                }
                authTriedRegister = true
                regAuth = DigestAuth.authorization(
                    challenge, username, password, "REGISTER", "sip:$domain",
                    nc.incrementAndGet(), newCnonce()
                )
                sendRegister(auth = regAuth)
            }
            in 200..299 -> {
                registered = true
                authTriedRegister = false
                Log.i(TAG, "SIP REGISTER OK")
                notify { it.onLoginSuccess() }
                scheduleReRegister(resp.header("Expires")?.toIntOrNull() ?: REGISTER_EXPIRES)
                startKeepalive()
            }
            else -> {
                registered = false
                val reason = "${resp.statusCode} ${resp.reason}"
                Log.w(TAG, "SIP REGISTER failed: $reason")
                notify { it.onLoginFailed(reason) }
            }
        }
    }

    private val reRegisterRunnable = Runnable {
        if (!intentionalClose && webSocket != null) {
            authTriedRegister = false
            sendRegister(auth = regAuth)
        }
    }

    private fun scheduleReRegister(expires: Int) {
        cancelReRegister()

        val delay = (expires.coerceAtLeast(30) * 1000L * 9) / 10
        mainHandler.postDelayed(reRegisterRunnable, delay)
    }

    private fun cancelReRegister() = mainHandler.removeCallbacks(reRegisterRunnable)

    private fun sendInvite(dialog: Dialog, sdp: String, auth: String?) {
        val branch = newBranch()
        dialog.inviteBranch = branch
        val target = dialog.toUri
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Via" to "SIP/2.0/$viaTransport $instanceId.invalid;branch=$branch;rport")
        headers.add("Max-Forwards" to "70")
        val fromName = dialog.fromDisplay.takeIf { it.isNotBlank() }?.let { "\"$it\" " } ?: ""
        headers.add("From" to "$fromName<${dialog.fromUri}>;tag=${dialog.localTag}")
        headers.add("To" to "<$target>")
        headers.add("Call-ID" to dialog.callId)
        headers.add("CSeq" to "${dialog.inviteCseq} INVITE")
        headers.add("Contact" to "<$contactUri>")
        headers.add("User-Agent" to USER_AGENT)
        headers.add("Supported" to "replaces, timer")
        auth?.let { headers.add("Proxy-Authorization" to it) }
        headers.add("Content-Type" to "application/sdp")
        pending[branch] = { resp -> onInviteResponse(dialog, resp) }
        sendMessage(SipMessage.request("INVITE", target, headers, sdp))
    }

    private fun onInviteResponse(dialog: Dialog, resp: SipMessage) {
        if (dialog.terminated) return

        if (resp.cseqMethod() == "CANCEL") return

        resp.toTag()?.let { dialog.remoteTag = it }
        resp.addrUri("Contact")?.let { dialog.remoteTarget = it }
        dialog.routeSet = resp.headersOf("Record-Route")

        when (resp.statusCode) {
            100 -> Unit
            180 -> notify { it.onRinging(dialog.callId) }
            183 -> {
                if (resp.body.isNotBlank()) notify { it.onMedia(dialog.callId, resp.body) }
                else notify { it.onRinging(dialog.callId) }
            }
            in 200..299 -> {

                ackTwoXX(dialog)
                dialog.established = true
                notify { it.onCallAnswered(dialog.callId, resp.body.ifBlank { null }) }
            }
            401, 407 -> {

                ackNonTwoXX(dialog, resp)
                if (dialog.inviteAuthTried) {
                    failCall(dialog, "Authentication rejected")
                    return
                }
                val challenge = resp.header("Proxy-Authenticate")?.let { DigestAuth.parseChallenge(it) }
                    ?: resp.header("WWW-Authenticate")?.let { DigestAuth.parseChallenge(it) }
                if (challenge == null) { failCall(dialog, "Malformed auth challenge"); return }
                dialog.inviteAuthTried = true
                dialog.inviteCseq = cseqCounter.getAndIncrement()
                val auth = DigestAuth.authorization(
                    challenge, username, password, "INVITE", dialog.toUri,
                    nc.incrementAndGet(), newCnonce()
                )
                sendInvite(dialog, dialog.localSdp ?: "", auth)
            }
            else -> {

                ackNonTwoXX(dialog, resp)
                failCall(dialog, "${resp.statusCode} ${resp.reason}")
            }
        }
    }

    private fun ackTwoXX(dialog: Dialog) {
        val branch = newBranch()
        val target = dialog.remoteTarget ?: dialog.toUri
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Via" to "SIP/2.0/$viaTransport $instanceId.invalid;branch=$branch;rport")
        headers.add("Max-Forwards" to "70")
        headers.add("From" to "<${dialog.fromUri}>;tag=${dialog.localTag}")
        headers.add("To" to withTag("<${dialog.toUri}>", dialog.remoteTag))
        headers.add("Call-ID" to dialog.callId)
        headers.add("CSeq" to "${dialog.inviteCseq} ACK")
        sendMessage(SipMessage.request("ACK", target, headers))
    }

    private fun ackNonTwoXX(dialog: Dialog, resp: SipMessage) {
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Via" to "SIP/2.0/$viaTransport $instanceId.invalid;branch=${dialog.inviteBranch};rport")
        headers.add("Max-Forwards" to "70")
        headers.add("From" to "<${dialog.fromUri}>;tag=${dialog.localTag}")
        headers.add("To" to withTag("<${dialog.toUri}>", resp.toTag()))
        headers.add("Call-ID" to dialog.callId)
        headers.add("CSeq" to "${dialog.inviteCseq} ACK")
        sendMessage(SipMessage.request("ACK", dialog.toUri, headers))
    }

    private fun sendCancel(dialog: Dialog) {

        val headers = ArrayList<Pair<String, String>>()
        headers.add("Via" to "SIP/2.0/$viaTransport $instanceId.invalid;branch=${dialog.inviteBranch};rport")
        headers.add("Max-Forwards" to "70")
        headers.add("From" to "<${dialog.fromUri}>;tag=${dialog.localTag}")
        headers.add("To" to "<${dialog.toUri}>")
        headers.add("Call-ID" to dialog.callId)
        headers.add("CSeq" to "${dialog.inviteCseq} CANCEL")
        sendMessage(SipMessage.request("CANCEL", dialog.toUri, headers))
    }

    private fun sendReInvite(dialog: Dialog, sdp: String) {
        val branch = newBranch()
        dialog.inviteBranch = branch
        dialog.inviteCseq = cseqCounter.getAndIncrement()
        val target = dialog.remoteTarget ?: dialog.toUri
        val headers = inDialogHeaders(dialog, "INVITE", branch)
        headers.add("Contact" to "<$contactUri>")
        headers.add("Content-Type" to "application/sdp")
        pending[branch] = { resp ->
            if (resp.statusCode in 200..299) ackTwoXX(dialog)
        }
        sendMessage(SipMessage.request("INVITE", target, headers, sdp))
    }

    private fun sendBye(dialog: Dialog, cause: String) {
        val branch = newBranch()
        val cseq = cseqCounter.getAndIncrement()
        val target = dialog.remoteTarget ?: dialog.toUri
        val headers = inDialogHeaders(dialog, "BYE", branch, cseq)

        headers.add("Reason" to "Q.850;cause=16;text=\"$cause\"")
        sendMessage(SipMessage.request("BYE", target, headers))
    }

    private fun rejectIncoming(dialog: Dialog, cause: String) {
        val invite = dialog.incomingInvite ?: return
        val (code, reason) = when (cause) {
            "USER_BUSY" -> 486 to "Busy Here"
            else -> 603 to "Decline"
        }
        respondTo(invite, code, reason, withToTag = dialog.localTag)
    }

    private fun failCall(dialog: Dialog, reason: String) {
        notify { it.onBye(dialog.callId, reason) }
        removeDialog(dialog.callId)
    }

    private fun handleRequest(msg: SipMessage) {
        when (msg.method) {
            "INVITE" -> handleIncomingInvite(msg)
            "BYE" -> {
                respondTo(msg, 200, "OK")
                val callId = msg.header("Call-ID") ?: return
                dialogs[callId]?.let {
                    notify { l -> l.onBye(callId, "NORMAL_CLEARING") }
                    removeDialog(callId)
                }
            }
            "CANCEL" -> {
                respondTo(msg, 200, "OK")
                val callId = msg.header("Call-ID") ?: return
                val dialog = dialogs[callId] ?: return
                dialog.incomingInvite?.let { respondTo(it, 487, "Request Terminated", dialog.localTag) }
                notify { l -> l.onBye(callId, "CANCEL") }
                removeDialog(callId)
            }
            "ACK" -> Unit
            "INFO" -> respondTo(msg, 200, "OK")
            "NOTIFY" -> respondTo(msg, 200, "OK")
            "OPTIONS" -> respondTo(msg, 200, "OK")
            "MESSAGE" -> respondTo(msg, 200, "OK")
            else -> respondTo(msg, 200, "OK")
        }
    }

    private fun handleIncomingInvite(msg: SipMessage) {
        val callId = msg.header("Call-ID") ?: return
        val existing = dialogs[callId]
        if (existing != null && existing.established) {

            respondTo(msg, 200, "OK", existing.localTag, existing.localSdp)
            return
        }

        respondTo(msg, 100, "Trying")

        val dialog = Dialog(callId, newTag(), isOutgoing = false).apply {
            remoteTag = msg.fromTag()
            fromUri = msg.addrUri("To") ?: fromUri
            toUri = msg.addrUri("From") ?: ""
            remoteTarget = msg.addrUri("Contact")
            inviteCseq = msg.cseqNumber()
            incomingInvite = msg
            routeSet = msg.headersOf("Record-Route")
        }
        dialogs[callId] = dialog

        respondTo(msg, 180, "Ringing", dialog.localTag)

        val callerNumber = sipUserOf(msg.addrUri("From"))
        val callerName = displayNameOf(msg.header("From")).ifBlank { callerNumber }
        notify { it.onIncomingCall(callId, callerName, callerNumber, msg.body) }
    }

    private fun respondTo(
        request: SipMessage,
        code: Int,
        reason: String,
        withToTag: String? = null,
        body: String? = null
    ) {
        val headers = ArrayList<Pair<String, String>>()
        request.headersOf("Via").forEach { headers.add("Via" to it) }
        headers.add("From" to (request.header("From") ?: ""))
        val to = request.header("To") ?: ""
        headers.add("To" to if (withToTag != null) withTag(to, withToTag) else to)
        headers.add("Call-ID" to (request.header("Call-ID") ?: ""))
        headers.add("CSeq" to (request.header("CSeq") ?: ""))
        if (code in 200..299 && request.method == "INVITE") headers.add("Contact" to "<$contactUri>")
        if (body != null) headers.add("Content-Type" to "application/sdp")
        sendMessage(SipMessage.response(code, reason, headers, body ?: ""))
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open; sending REGISTER")
            mainHandler.post { sendRegister() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {

            if (text.isBlank()) return
            val msg = SipMessage.parse(text)
            if (msg == null) { Log.w(TAG, "Unparseable SIP frame: $text"); return }
            mainHandler.post { dispatch(msg) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed ($code $reason)")
            registered = false
            cancelKeepalive(); cancelReRegister()
            if (!intentionalClose) notify { it.onDisconnected(reason.ifBlank { "closed" }) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure", t)
            registered = false
            cancelKeepalive(); cancelReRegister()
            if (!intentionalClose) notify { it.onDisconnected(t.message ?: "connection failed") }
        }
    }

    private fun dispatch(msg: SipMessage) {
        if (msg.isResponse) {
            Log.d(TAG, "<- ${msg.statusCode} ${msg.reason} (${msg.cseqMethod()}) branch=${msg.topViaBranch()}")
            val branch = msg.topViaBranch()
            val cb = branch?.let { pending[it] }
            if (cb != null) {

                if (msg.statusCode >= 200) pending.remove(branch)
                cb(msg)
            } else {
                Log.d(TAG, "Response with no pending transaction: ${msg.statusCode} ${msg.cseqMethod()}")
            }
        } else {
            Log.d(TAG, "<- ${msg.method} ${msg.requestUri}")
            handleRequest(msg)
        }
    }

    private fun sendMessage(msg: SipMessage) {
        val text = msg.encode()
        val line = if (msg.isRequest) "${msg.method} ${msg.requestUri}" else "${msg.statusCode} ${msg.reason}"
        Log.d(TAG, "-> $line")
        val ok = webSocket?.send(text) ?: false
        if (!ok) Log.w(TAG, "Failed to send $line — socket not open")
    }

    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            webSocket?.send("\r\n\r\n")
            mainHandler.postDelayed(this, KEEPALIVE_MS)
        }
    }

    private fun startKeepalive() {
        cancelKeepalive()
        mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_MS)
    }

    private fun cancelKeepalive() = mainHandler.removeCallbacks(keepaliveRunnable)

    private fun inDialogHeaders(
        dialog: Dialog,
        method: String,
        branch: String,
        cseq: Int = cseqCounter.getAndIncrement()
    ): ArrayList<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Via" to "SIP/2.0/$viaTransport $instanceId.invalid;branch=$branch;rport")
        headers.add("Max-Forwards" to "70")

        headers.add("From" to "<${dialog.fromUri}>;tag=${dialog.localTag}")
        headers.add("To" to withTag("<${dialog.toUri}>", dialog.remoteTag))
        headers.add("Call-ID" to dialog.callId)
        headers.add("CSeq" to "$cseq $method")
        dialog.routeSet.forEach { headers.add("Route" to it) }
        headers.add("User-Agent" to USER_AGENT)
        return headers
    }

    private fun removeDialog(callId: String) {
        dialogs.remove(callId)?.let { it.terminated = true }
    }

    private fun withTag(headerValue: String, tag: String?): String {
        if (tag == null) return headerValue
        return if (headerValue.contains("tag=")) headerValue else "$headerValue;tag=$tag"
    }

    private fun setSdpDirection(sdp: String, direction: String): String {
        val directions = setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive")
        val lines = sdp.lines().toMutableList()
        var replaced = false
        for (i in lines.indices) {
            if (lines[i].trimEnd() in directions) { lines[i] = "a=$direction"; replaced = true }
        }
        if (!replaced) {

            val idx = lines.indexOfFirst { it.startsWith("m=audio") }
            if (idx >= 0) lines.add(idx + 1, "a=$direction")
        }
        return lines.joinToString("\r\n")
    }

    private fun sipUserOf(uri: String?): String =
        uri?.substringAfter("sip:")?.substringBefore("@")?.substringBefore(";").orEmpty()

    private fun displayNameOf(fromHeader: String?): String {
        if (fromHeader == null) return ""
        val before = fromHeader.substringBefore("<")
        return before.trim().trim('"')
    }

    private inline fun notify(crossinline block: (Listener) -> Unit) {
        val l = listener ?: return
        mainHandler.post { block(l) }
    }

    private fun newCallId() = "${UUID.randomUUID()}"
    private fun newTag() = UUID.randomUUID().toString().take(10)
    private fun newBranch() = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
    private fun newCnonce() = UUID.randomUUID().toString().replace("-", "")

    private fun buildHttpClient(): OkHttpClient {

        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)

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
        private const val TAG = "SipClient"
        private const val USER_AGENT = "EmakRtcPhone"
        private const val REGISTER_EXPIRES = 600
        private const val KEEPALIVE_MS = 30_000L

        const val ALLOW_INSECURE_TLS = true
    }
}
