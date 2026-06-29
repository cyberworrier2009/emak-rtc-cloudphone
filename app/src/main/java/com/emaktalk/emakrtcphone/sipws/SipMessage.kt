package com.emaktalk.emakrtcphone.sipws

class SipMessage private constructor(
    val isRequest: Boolean,

    val method: String,

    val requestUri: String,

    val statusCode: Int,

    val reason: String,

    val headers: List<Pair<String, String>>,
    val body: String
) {
    val isResponse: Boolean get() = !isRequest

    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun headersOf(name: String): List<String> =
        headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun topViaBranch(): String? =
        header("Via")?.let { BRANCH_RE.find(it)?.groupValues?.get(1) }

    fun fromTag(): String? = header("From")?.let { TAG_RE.find(it)?.groupValues?.get(1) }

    fun toTag(): String? = header("To")?.let { TAG_RE.find(it)?.groupValues?.get(1) }

    fun cseqNumber(): Int = header("CSeq")?.trim()?.substringBefore(' ')?.toIntOrNull() ?: 0

    fun cseqMethod(): String = header("CSeq")?.trim()?.substringAfter(' ', "")?.trim().orEmpty()

    fun addrUri(name: String): String? {
        val raw = header(name) ?: return null
        val angled = ANGLE_RE.find(raw)?.groupValues?.get(1)

        return angled ?: raw.substringBefore(';').trim().ifBlank { null }
    }

    fun encode(): String {
        val sb = StringBuilder()
        if (isRequest) sb.append("$method $requestUri SIP/2.0\r\n")
        else sb.append("SIP/2.0 $statusCode $reason\r\n")
        for ((name, value) in headers) {
            if (name.equals("Content-Length", ignoreCase = true)) continue
            sb.append("$name: $value\r\n")
        }
        // Content-Length is mandatory for sofia-sip; emit a computed one (in
        // bytes) on every message so an empty REGISTER/ACK isn't treated as a
        // truncated body and dropped.
        sb.append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        sb.append("\r\n")
        sb.append(body)
        return sb.toString()
    }

    companion object {
        private val BRANCH_RE = Regex("branch=([^;,\\s]+)")
        private val TAG_RE = Regex("tag=([^;,\\s>]+)")
        private val ANGLE_RE = Regex("<([^>]+)>")

        private val COMPACT = mapOf(
            "i" to "Call-ID", "m" to "Contact", "e" to "Content-Encoding",
            "l" to "Content-Length", "c" to "Content-Type", "f" to "From",
            "s" to "Subject", "k" to "Supported", "t" to "To", "v" to "Via"
        )

        fun parse(raw: String): SipMessage? {

            val text = raw.replace("\r\n", "\n")
            val sep = text.indexOf("\n\n")
            val head = if (sep >= 0) text.substring(0, sep) else text
            val body = if (sep >= 0) text.substring(sep + 2) else ""
            val lines = head.split("\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null

            val start = lines.first()
            val headers = ArrayList<Pair<String, String>>(lines.size)
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val rawName = line.substring(0, colon).trim()
                val name = COMPACT[rawName.lowercase()] ?: rawName
                val value = line.substring(colon + 1).trim()
                headers.add(name to value)
            }

            return if (start.startsWith("SIP/2.0")) {

                val parts = start.split(" ", limit = 3)
                val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val reason = parts.getOrElse(2) { "" }
                SipMessage(false, "", "", code, reason, headers, body)
            } else {

                val parts = start.split(" ")
                if (parts.size < 3) return null
                SipMessage(true, parts[0], parts[1], 0, "", headers, body)
            }
        }

        fun request(method: String, requestUri: String, headers: List<Pair<String, String>>, body: String = "") =
            SipMessage(true, method, requestUri, 0, "", headers, body)

        fun response(code: Int, reason: String, headers: List<Pair<String, String>>, body: String = "") =
            SipMessage(false, "", "", code, reason, headers, body)
    }
}
