package com.emaktalk.emakrtcphone.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests for [TurnServerApi.parseIceServers], driven by the exact
 * response shape the backend returns for `/chat_app/turn_server`.
 */
class TurnServerApiTest {

    private val api = TurnServerApi()

    /** The real sample payload from the Cloudflare-backed endpoint. */
    private val sample = """
        {
          "iceServers": [
            {
              "urls": [
                "stun:stun.cloudflare.com:3478",
                "stun:stun.cloudflare.com:53"
              ]
            },
            {
              "urls": [
                "turn:turnv2.realtime.cloudflare.com:3478?transport=udp",
                "turn:turn.cloudflare.com:3478?transport=tcp",
                "turns:turn.cloudflare.com:5349?transport=tcp",
                "turn:turn.cloudflare.com:53?transport=udp",
                "turn:turn.cloudflare.com:80?transport=tcp",
                "turns:turn.cloudflare.com:443?transport=tcp"
              ],
              "username": "g0dc478da9facb17664a871e7ab8395b02c83dde91fa06f471133841da54506f",
              "credential": "75ba33282b7c673463c597640f7c81eb8eec145d2500b6c8f5ff737c72f9a2f4"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesStunAndTurnServers() {
        val servers = api.parseIceServers(sample)

        // Two ICE server entries: a STUN block and a credentialed TURN block.
        assertEquals(2, servers.size)

        val stun = servers[0]
        assertEquals(listOf("stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"), stun.urls)
        // STUN has no credentials.
        assertTrue(stun.username.isNullOrEmpty())

        val turn = servers[1]
        assertEquals(6, turn.urls.size)
        assertEquals("turn:turnv2.realtime.cloudflare.com:3478?transport=udp", turn.urls[0])
        assertEquals(
            "g0dc478da9facb17664a871e7ab8395b02c83dde91fa06f471133841da54506f",
            turn.username
        )
        assertEquals(
            "75ba33282b7c673463c597640f7c81eb8eec145d2500b6c8f5ff737c72f9a2f4",
            turn.password
        )
    }

    @Test
    fun handlesUrlsAsSingleString() {
        val body = """{ "iceServers": [ { "urls": "stun:stun.example.com:3478" } ] }"""
        val servers = api.parseIceServers(body)
        assertEquals(1, servers.size)
        assertEquals(listOf("stun:stun.example.com:3478"), servers[0].urls)
    }

    @Test
    fun skipsEntriesWithNoUrls() {
        val body = """{ "iceServers": [ { "username": "u", "credential": "c" }, { "urls": [] } ] }"""
        assertTrue(api.parseIceServers(body).isEmpty())
    }

    @Test
    fun returnsEmptyWhenNoIceServersKey() {
        assertTrue(api.parseIceServers("""{ "foo": "bar" }""").isEmpty())
    }
}
