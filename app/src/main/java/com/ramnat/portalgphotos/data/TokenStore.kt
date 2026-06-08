package com.ramnat.portalgphotos.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Reads the sideloaded OAuth config (token.json, produced by the auth-helper and
 * pushed via adb) and exchanges the refresh token for short-lived access tokens.
 * Pure OkHttp + org.json — no Google/GMS libraries.
 */
class TokenStore(private val configFile: File, private val http: OkHttpClient) {

    data class Config(
        val clientId: String,
        val clientSecret: String,
        val refreshToken: String,
        val tokenUri: String,
    )

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    fun config(): Config? {
        if (!configFile.exists()) return null
        return try {
            val o = JSONObject(configFile.readText())
            Config(
                clientId = o.getString("client_id"),
                clientSecret = o.getString("client_secret"),
                refreshToken = o.getString("refresh_token"),
                tokenUri = o.optString("token_uri", "https://oauth2.googleapis.com/token"),
            )
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun accessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < expiresAtMs - 60_000) return it }
        val cfg = config() ?: throw IOException("No token config on device (token.json missing)")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", cfg.refreshToken)
            .add("client_id", cfg.clientId)
            .add("client_secret", cfg.clientSecret)
            .build()
        val req = Request.Builder().url(cfg.tokenUri).post(form).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Token refresh failed ${resp.code}: $body")
            val o = JSONObject(body)
            val token = o.getString("access_token")
            val ttl = o.optLong("expires_in", 3600L)
            cachedToken = token
            expiresAtMs = now + ttl * 1000L
            return token
        }
    }
}
