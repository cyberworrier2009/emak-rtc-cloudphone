package com.emaktalk.emakrtcphone.ui.dialer

data class ContactSuggestion(
    val name: String,
    val company: String
)

object ContactDirectory {

    private val byExtension = mapOf(
        "415" to ContactSuggestion("Tomás Echeverría", "Tessera Capital")
    )

    fun match(number: String): ContactSuggestion? {
        if (number.isEmpty()) return null
        return byExtension[number.trim()]
    }
}
