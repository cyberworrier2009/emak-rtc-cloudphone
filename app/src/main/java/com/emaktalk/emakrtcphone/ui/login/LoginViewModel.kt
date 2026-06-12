package com.emaktalk.emakrtcphone.ui.login

import androidx.lifecycle.ViewModel
import com.emaktalk.emakrtcphone.auth.AuthManager

class LoginViewModel : ViewModel() {

    val state = AuthManager.state
    val error = AuthManager.error

    fun login(username: String, password: String) {
        AuthManager.login(username, password)
    }
}
