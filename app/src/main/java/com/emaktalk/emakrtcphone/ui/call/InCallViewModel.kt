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

    // Multi-call / conference
    fun holdCall() = SipCoreManager.holdCall()
    fun resumeCall() = SipCoreManager.resumeCall()
    fun addCall() = SipCoreManager.addCall()
    fun cancelAddCall() = SipCoreManager.cancelAddCall()
    fun placeSecondCall(number: String) = SipCoreManager.placeSecondCall(number)
    fun swapCalls() = SipCoreManager.swapCalls()
    fun mergeCalls() = SipCoreManager.mergeCalls()
}
