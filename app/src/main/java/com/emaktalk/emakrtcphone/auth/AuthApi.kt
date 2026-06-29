package com.emaktalk.emakrtcphone.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthApi(
    private val loginUrl: String = DEFAULT_LOGIN_URL,
    private val refreshUrl: String = DEFAULT_REFRESH_URL,
    private val client: OkHttpClient = defaultClient
) {

    suspend fun login(username: String, password: String): Result<AuthTokens> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .toString()
                    .toRequestBody(JSON)

                val request = Request.Builder()
                    .url(loginUrl)
                    .post(payload)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(errorMessage(body, response.code))
                    }
                    parseTokens(body)
                }
            }
        }

    suspend fun refresh(refreshToken: String): Result<AuthTokens> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject()
                    .put("refresh_token", refreshToken)
                    .toString()
                    .toRequestBody(JSON)

                val request = Request.Builder()
                    .url(refreshUrl)
                    .post(payload)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(errorMessage(body, response.code))
                    }
                    parseTokens(body)
                }
            }
        }

    private fun parseTokens(body: String): AuthTokens {
        val json = JSONObject(body)
        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) {
            throw IOException("Login response did not contain an access token")
        }
        val now = System.currentTimeMillis()
        return AuthTokens(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token"),
            tokenType = json.optString("token_type", "Bearer"),
            accessExpiresAtMillis = now + json.optLong("expires_in", 0L) * 1000L,
            refreshExpiresAtMillis = now + json.optLong("refresh_expires_in", 0L) * 1000L
        )
    }

    private fun errorMessage(body: String, code: Int): String {
        val fromJson = runCatching {
            val json = JSONObject(body)
            when {
                json.has("message") -> json.optString("message")
                json.has("error_description") -> json.optString("error_description")
                json.has("error") -> json.optString("error")
                json.has("detail") -> json.optString("detail")
                else -> null
            }
        }.getOrNull()

        return when {
            !fromJson.isNullOrBlank() -> fromJson
            code == 401 || code == 403 -> "Invalid username or password"
            else -> "Login failed (HTTP $code)"
        }
    }

    companion object {
        const val DEFAULT_LOGIN_URL = "http://alpha.emaktech.com/api/v1/auth/login"
        const val DEFAULT_REFRESH_URL = "http://alpha.emaktech.com/api/v1/auth/refresh"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
