package com.emaktalk.emakrtcphone

import android.app.Application
import com.emaktalk.emakrtcphone.auth.AuthManager
import com.emaktalk.emakrtcphone.sip.SipCoreManager

class EmakRtcPhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AuthManager.initialize(this)

        SipCoreManager.initialize(this)

        SipCoreManager.restoreSession()
    }
}
