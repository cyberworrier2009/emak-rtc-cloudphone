package com.emaktalk.emakrtcphone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.emaktalk.emakrtcphone.auth.AuthManager
import com.emaktalk.emakrtcphone.sip.SipCoreManager
import com.emaktalk.emakrtcphone.ui.account.AccountScreen
import com.emaktalk.emakrtcphone.ui.call.InCallScreen
import com.emaktalk.emakrtcphone.ui.dialer.DialerScreen
import com.emaktalk.emakrtcphone.ui.login.ExtensionSelectScreen
import com.emaktalk.emakrtcphone.ui.login.LoginScreen

private object Routes {
    const val LOGIN = "login"
    const val EXTENSION_SELECT = "extension_select"
    const val DIALER = "dialer"
    const val ACCOUNT = "account"
    const val IN_CALL = "incall"
}

@Composable
fun EmakRtcPhoneApp() {
    val navController = rememberNavController()
    val authState by AuthManager.state.collectAsState()
    val callState by SipCoreManager.callState.collectAsState()
    val hasActiveCall = callState != null

    // The user must sign in before reaching the dialer. We pick the start
    // destination from the session restored at launch (so a signed-in user
    // doesn't flash the login screen) and react to later sign-in/out below.
    val startDestination = if (AuthManager.isLoggedIn) Routes.DIALER else Routes.LOGIN

    // Move between the login gate and the app whenever auth state changes.
    LaunchedEffect(authState) {
        when (authState) {
            AuthManager.State.SelectingExtension -> {
                navController.navigate(Routes.EXTENSION_SELECT) {
                    launchSingleTop = true
                }
            }
            AuthManager.State.LoggedIn -> {
                navController.navigate(Routes.DIALER) {
                    // Clear the login + extension-picker so Back can't return to them.
                    popUpTo(Routes.LOGIN) { inclusive = true }
                    launchSingleTop = true
                }
            }
            AuthManager.State.LoggedOut -> {
                navController.navigate(Routes.LOGIN) {
                    // Drop the whole back stack so Back can't return into the app.
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
            else -> Unit
        }
    }

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

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LOGIN) {
            LoginScreen()
        }
        composable(Routes.EXTENSION_SELECT) {
            ExtensionSelectScreen()
        }
        composable(Routes.DIALER) {
            DialerScreen(
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onLogout = { AuthManager.logout() }
            )
        }
        composable(Routes.ACCOUNT) {
            AccountScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.IN_CALL) {
            InCallScreen()
        }
    }
}
