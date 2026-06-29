package com.emaktalk.emakrtcphone.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class EmakMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated; publishing to PushTokenStore")
        PushTokenStore.update(
            PushToken(
                provider = "fcm.googleapis.com",
                prid = token,
                param = SENDER_ID
            )
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {

        Log.i(TAG, "FCM wakeup: data=${message.data} from=${message.from}")
    }

    companion object {
        private const val TAG = "EmakFcm"

        private const val SENDER_ID = "REPLACE_WITH_FIREBASE_SENDER_ID"
    }
}
