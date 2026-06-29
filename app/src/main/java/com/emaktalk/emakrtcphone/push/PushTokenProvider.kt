package com.emaktalk.emakrtcphone.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PushToken(

    val provider: String,

    val prid: String,

    val param: String
) {

    fun toContactParams(): String =
        "pn-provider=$provider;pn-prid=$prid;pn-param=$param;pn-silent=1;pn-timeout=0"
}

object PushTokenStore {
    private val _token = MutableStateFlow<PushToken?>(null)
    val token: StateFlow<PushToken?> = _token.asStateFlow()

    fun update(token: PushToken?) {
        _token.value = token
    }
}
