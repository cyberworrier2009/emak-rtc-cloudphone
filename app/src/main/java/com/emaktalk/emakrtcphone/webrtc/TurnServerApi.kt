package com.emaktalk.emakrtcphone.webrtc

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TurnServerApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient
) {

    suspend fun fetchIceServers(
        userId: String,
        accessToken: String?
    ): Result<List<PeerConnection.IceServer>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedUser = URLEncoder.encode(userId, "UTF-8")

            val builder = Request.Builder()
                .url("$baseUrl/chat_app/turn_server?user=$encodedUser")
                .post(EMPTY_JSON_BODY)
                .header("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $accessToken")
            }

            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("TURN server request failed (HTTP ${response.code})")
                }
                parseIceServers(body)
            }
        }
    }

    internal fun parseIceServers(body: String): List<PeerConnection.IceServer> {
        val servers = JSONObject(body).optJSONArray("iceServers") ?: return emptyList()
        val result = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until servers.length()) {
            val entry = servers.optJSONObject(i) ?: continue

            val urls = mutableListOf<String>()
            when (val urlsValue = entry.opt("urls")) {
                is JSONArray -> for (j in 0 until urlsValue.length()) {
                    urlsValue.optString(j).takeIf { it.isNotBlank() }?.let(urls::add)
                }
                is String -> urlsValue.takeIf { it.isNotBlank() }?.let(urls::add)
            }
            if (urls.isEmpty()) continue

            val iceBuilder = PeerConnection.IceServer.builder(urls)
            entry.optString("username").takeIf { it.isNotBlank() }?.let(iceBuilder::setUsername)
            entry.optString("credential").takeIf { it.isNotBlank() }?.let(iceBuilder::setPassword)
            result.add(iceBuilder.createIceServer())
        }
        Log.i(TAG, "Parsed ${result.size} ICE server entr${if (result.size == 1) "y" else "ies"}")
        return result
    }

    companion object {
        private const val TAG = "TurnServerApi"

        const val DEFAULT_BASE_URL = "http://alpha.emaktech.com/api/v0"

        private val EMPTY_JSON_BODY = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
