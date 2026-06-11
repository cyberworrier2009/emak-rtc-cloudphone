package com.emaktalk.emakrtcphone.ui.account

import androidx.lifecycle.ViewModel
import com.emaktalk.emakrtcphone.sip.SipCoreManager
import com.emaktalk.emakrtcphone.sip.VertoTransport

class AccountViewModel : ViewModel() {

    val registrationState = SipCoreManager.registrationState
    val registrationMessage = SipCoreManager.registrationMessage

    /** Previously-saved account, used to prefill the form so a signed-in user
     *  sees their details instead of blank fields. */
    val savedAccount = SipCoreManager.savedAccount()

    fun register(username: String, password: String, domain: String, transport: VertoTransport) {
        if (username.isBlank() || domain.isBlank()) return
        SipCoreManager.register(
            username = username.trim(),
            password = password,
            domain = domain.trim(),
            transport = transport
        )
    }

    fun unregister() = SipCoreManager.unregister()
}
