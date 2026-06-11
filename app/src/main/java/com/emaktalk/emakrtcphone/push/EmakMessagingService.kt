package com.emaktalk.emakrtcphone.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages from a FreeSWITCH-side push bridge and publishes token
 * rotations to [PushTokenStore].
 *
 * Wire-up:
 *  1. Drop your real `google-services.json` into `app/` (the checked-in one is a
 *     build placeholder).
 *  2. On the FreeSWITCH side, run a bridge that maps each Verto user to its FCM
 *     token (captured via your provisioning flow) and, when an inbound call
 *     arrives for that user, sends a data-only FCM message to the token.
 *  3. On [onMessageReceived] the app re-opens the Verto WebSocket (re-runs
 *     register) so it's connected when the `verto.invite` is delivered.
 *  4. Keep the FCM payload data-only (no `notification:` field) so this class
 *     drives the call UI, not the system notification shade.
 */
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
        // The wake-up itself is what we needed: the OS has unfrozen us. The Verto
        // client's auto-reconnect (or an explicit re-register) then pulls the
        // incoming verto.invite off a freshly-opened socket. Extra metadata
        // (caller id, etc.) could be surfaced here.
        Log.i(TAG, "FCM wakeup: data=${message.data} from=${message.from}")
    }

    companion object {
        private const val TAG = "EmakFcm"

        // Replace with your Firebase project number (sender ID), or pull from
        // google-services.json via a generated resource.
        private const val SENDER_ID = "REPLACE_WITH_FIREBASE_SENDER_ID"
    }
}
