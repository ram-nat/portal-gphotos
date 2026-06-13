package com.ramnat.portalgphotos.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
/**
 * Thrown when the stored refresh token is permanently invalid (expired or revoked —
 * Google returns `invalid_grant`). A retry of the same refresh can never succeed; the
 * user must re-authenticate. The dead token is cleared before this is thrown.
 */
class RefreshTokenInvalidException(message: String) : IOException(message)

class TokenStore(context: Context, private val configFile: File, private val http: OkHttpClient) {

    data class Config(
        val clientId: String,
        val clientSecret: String,
        val refreshToken: String,
        val tokenUri: String,
    )

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    val prefs = EncryptedSharedPreferences.create(
        context,
        "secret_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun config(): Config? {
        val rToken = prefs.getString("refresh_token", null)
        if (rToken != null) {
            return Config(
                clientId = prefs.getString("client_id", "")!!,
                clientSecret = prefs.getString("client_secret", "")!!,
                refreshToken = rToken,
                tokenUri = prefs.getString("token_uri", "https://oauth2.googleapis.com/token")!!
            )
        }

        if (!configFile.exists()) {
            return null
        }
        return try {
            val o = JSONObject(configFile.readText())
            val cfg = Config(
                clientId = o.getString("client_id"),
                clientSecret = o.getString("client_secret"),
                refreshToken = o.getString("refresh_token"),
                tokenUri = o.optString("token_uri", "https://oauth2.googleapis.com/token"),
            )
            saveConfig(cfg)
            configFile.delete()
            cfg
        } catch (e: Exception) {
            null
        }
    }

    fun saveConfig(cfg: Config) {
        prefs.edit()
            .putString("client_id", cfg.clientId)
            .putString("client_secret", cfg.clientSecret)
            .putString("refresh_token", cfg.refreshToken)
            .putString("token_uri", cfg.tokenUri)
            .apply()
    }

    /**
     * Drop the dead refresh token (keep the client creds so on-device re-auth still works).
     * After this, [config] returns null, so the app routes to the sign-in screen.
     */
    fun clearRefreshToken() {
        cachedToken = null
        expiresAtMs = 0L
        prefs.edit().remove("refresh_token").apply()
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
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
                if (err == "invalid_grant") {
                    clearRefreshToken()
                    throw RefreshTokenInvalidException(
                        "Your Google sign-in expired or was revoked. Sign in again."
                    )
                }
                throw IOException("Token refresh failed ${resp.code}: $body")
            }
            val o = JSONObject(body)
            val token = o.getString("access_token")
            val ttl = o.optLong("expires_in", 3600L)
            cachedToken = token
            expiresAtMs = now + ttl * 1000L
            return token
        }
    }
}
