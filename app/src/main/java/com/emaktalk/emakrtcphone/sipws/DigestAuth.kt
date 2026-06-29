package com.emaktalk.emakrtcphone.sipws

import java.security.MessageDigest

object DigestAuth {

    data class Challenge(
        val realm: String,
        val nonce: String,
        val opaque: String?,

        val qop: String?,
        val algorithm: String
    )

    fun parseChallenge(value: String): Challenge? {
        val body = value.trim().removePrefix("Digest").trim()
        val params = HashMap<String, String>()

        for (part in body.split(",")) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq).trim().lowercase()
            val v = part.substring(eq + 1).trim().trim('"')
            params[k] = v
        }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        return Challenge(
            realm = realm,
            nonce = nonce,
            opaque = params["opaque"],
            qop = qop,
            algorithm = params["algorithm"] ?: "MD5"
        )
    }

    fun authorization(
        challenge: Challenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        nc: Int,
        cnonce: String
    ): String {
        val ha1 = md5("$username:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")
        val response: String
        val sb = StringBuilder()
        sb.append("Digest username=\"$username\"")
        sb.append(", realm=\"${challenge.realm}\"")
        sb.append(", nonce=\"${challenge.nonce}\"")
        sb.append(", uri=\"$uri\"")
        if (challenge.qop == "auth") {
            val ncHex = "%08x".format(nc)
            response = md5("$ha1:${challenge.nonce}:$ncHex:$cnonce:auth:$ha2")
            sb.append(", qop=auth, nc=$ncHex, cnonce=\"$cnonce\"")
        } else {
            response = md5("$ha1:${challenge.nonce}:$ha2")
        }
        sb.append(", response=\"$response\"")
        sb.append(", algorithm=${challenge.algorithm}")
        challenge.opaque?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
