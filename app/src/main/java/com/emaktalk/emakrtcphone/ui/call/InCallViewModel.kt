package com.emaktalk.emakrtcphone.ui.call

import androidx.lifecycle.ViewModel
import com.emaktalk.emakrtcphone.audio.AudioRoute
import com.emaktalk.emakrtcphone.sip.SipCoreManager

class InCallViewModel : ViewModel() {

    val callState = SipCoreManager.callState

    fun answer() = SipCoreManager.acceptCall()
    fun hangUp() = SipCoreManager.terminateCall()
    fun toggleMute() = SipCoreManager.toggleMute()
    fun toggleSpeaker() = SipCoreManager.toggleSpeaker()
    fun setAudioRoute(route: AudioRoute) = SipCoreManager.setAudioRoute(route)
    fun sendDtmf(digit: Char) = SipCoreManager.sendDtmf(digit)
    fun forceReconnect() = SipCoreManager.forceReconnect()
}
