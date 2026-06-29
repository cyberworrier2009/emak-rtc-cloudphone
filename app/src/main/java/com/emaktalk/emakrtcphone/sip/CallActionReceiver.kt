package com.emaktalk.emakrtcphone.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> {
                Log.i(TAG, "Incoming call declined from notification")
                SipCoreManager.terminateCall()
            }
        }
    }

    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_DECLINE = "com.emaktalk.emakrtcphone.action.DECLINE_CALL"
    }
}
