package com.emaktalk.emakrtcphone

import android.app.Application
import com.emaktalk.emakrtcphone.sip.SipCoreManager

class EmakRtcPhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SipCoreManager.initialize(this)
        // Log a previously signed-in user straight back in so the session
        // survives the app being closed or the device rebooting.
        SipCoreManager.restoreSession()
    }
}
