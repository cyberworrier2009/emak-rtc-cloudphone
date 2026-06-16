package com.emaktalk.emakrtcphone.ui.dialer

/** A contact that matches the number currently being dialed. */
data class ContactSuggestion(
    val name: String,
    val company: String
)

/**
 * Looks up a contact for the dialed number so the dialer can surface a
 * suggestion chip (as in the design).
 *
 * This is a local placeholder directory; replace [match] with a real contacts
 * / directory lookup when that data source is wired up.
 */
object ContactDirectory {

    private val byExtension = mapOf(
        "415" to ContactSuggestion("Tomás Echeverría", "Tessera Capital")
    )

    fun match(number: String): ContactSuggestion? {
        if (number.isEmpty()) return null
        return byExtension[number.trim()]
    }
}
