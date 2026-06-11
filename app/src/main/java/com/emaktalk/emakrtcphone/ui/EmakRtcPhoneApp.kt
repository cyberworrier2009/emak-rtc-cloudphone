package com.emaktalk.emakrtcphone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.emaktalk.emakrtcphone.sip.SipCoreManager
import com.emaktalk.emakrtcphone.ui.account.AccountScreen
import com.emaktalk.emakrtcphone.ui.call.InCallScreen
import com.emaktalk.emakrtcphone.ui.dialer.DialerScreen

private object Routes {
    const val DIALER = "dialer"
    const val ACCOUNT = "account"
    const val IN_CALL = "incall"
}

@Composable
fun EmakRtcPhoneApp() {
    val navController = rememberNavController()
    val callState by SipCoreManager.callState.collectAsState()
    val hasActiveCall = callState != null

    // Drive call screen navigation from the core's call state so that both
    // outgoing and incoming calls surface the in-call UI automatically.
    LaunchedEffect(hasActiveCall) {
        if (hasActiveCall) {
            navController.navigate(Routes.IN_CALL) {
                launchSingleTop = true
            }
        } else {
            navController.popBackStack(Routes.DIALER, inclusive = false)
        }
    }

    NavHost(navController = navController, startDestination = Routes.DIALER) {
        composable(Routes.DIALER) {
            DialerScreen(onOpenAccount = { navController.navigate(Routes.ACCOUNT) })
        }
        composable(Routes.ACCOUNT) {
            AccountScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.IN_CALL) {
            InCallScreen()
        }
    }
}
