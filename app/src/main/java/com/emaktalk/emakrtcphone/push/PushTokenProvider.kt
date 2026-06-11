package com.emaktalk.emakrtcphone.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Push registration token for waking the app on incoming calls.
 *
 * The Verto model keeps a persistent WebSocket while the app is alive, but when
 * Android freezes the process (doze / swiped away) a push wakeup is still needed
 * to re-open the socket in time to receive the `verto.invite`. That requires a
 * FreeSWITCH-side bridge that, on an incoming call for this user, sends a
 * data-only FCM message to [PushToken.prid]; the app wakes, re-runs
 * [com.emaktalk.emakrtcphone.sip.SipCoreManager.register], and answers.
 *
 * To wire FCM: the [EmakMessagingService] derives the token and calls
 * [PushTokenStore.update]. The contact-param renderer below is retained from the
 * RFC 8599 SIP world in case you also run a SIP-WS edge; Verto itself doesn't
 * consume it.
 */
data class PushToken(
    /** e.g. "fcm.googleapis.com" or "apns". */
    val provider: String,
    /** The device push token. */
    val prid: String,
    /** Usually the FCM sender ID or APNs topic. */
    val param: String
) {
    /** Renders as an RFC 8599 SIP contact parameter string (semicolon-separated). */
    fun toContactParams(): String =
        "pn-provider=$provider;pn-prid=$prid;pn-param=$param;pn-silent=1;pn-timeout=0"
}

object PushTokenStore {
    private val _token = MutableStateFlow<PushToken?>(null)
    val token: StateFlow<PushToken?> = _token.asStateFlow()

    fun update(token: PushToken?) {
        _token.value = token
    }
}
