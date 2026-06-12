package com.emaktalk.emakrtcphone

import android.app.Application
import com.emaktalk.emakrtcphone.auth.AuthManager
import com.emaktalk.emakrtcphone.sip.SipCoreManager

class EmakRtcPhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore the saved login (tokens) before the first frame so an
        // already-signed-in user goes straight to the dialer instead of the
        // login screen.
        AuthManager.initialize(this)

        SipCoreManager.initialize(this)
        // Log a previously signed-in user straight back in so the session
        // survives the app being closed or the device rebooting.
        SipCoreManager.restoreSession()
    }
}
