package com.ramnat.portalgphotos.data

import android.net.Uri
import com.ramnat.portalgphotos.BuildConfig
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Runs Google's loopback OAuth flow entirely on the Portal — the same flow the laptop `auth-helper`
 * runs, ported to Kotlin. We bind a one-shot [ServerSocket] on the loopback interface; the Portal's
 * signed-in Chromium completes consent and Google redirects to `http://127.0.0.1:<port>/`, which
 * all apps on the device can reach (Android shares the loopback interface). We capture the code,
 * exchange it for a refresh token, and write the same `token.json` [TokenStore] reads — so nothing
 * downstream changes.
 *
 * Mirrors `auth-helper/src/portal_auth/oauth.py`: Desktop client + client_secret, no PKCE.
 *
 * The client credentials come from a pushed [clientFile] if present (so a prebuilt APK can be
 * deployed with just `adb push client_secret.json …` — no rebuild), otherwise from [BuildConfig]
 * (baked in via local.properties at build time). The file is Google's downloaded
 * `client_secret.json` as-is — the `installed`/`web` wrapper is unwrapped.
 */
class OnDeviceAuth(
        private val http: OkHttpClient,
        private val tokens: TokenStore,
        private val clientFile: File? = null,
) {
    private val clientId: String
        get() = resolveCreds(clientFile).first
    private val clientSecret: String
        get() = resolveCreds(clientFile).second

    private val authUri = "https://accounts.google.com/o/oauth2/v2/auth"
    private val tokenUri = "https://oauth2.googleapis.com/token"
    private val scope = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    /**
     * Prefer a pushed client file; fall back to build-time creds. Accepts Google's
     * `client_secret.json` (`installed`/`web` wrapper) or a flat `{client_id, client_secret}`.
     */
    private fun resolveCreds(clientFile: File?): Pair<String, String> {
        val savedId = tokens.prefs.getString("client_id", null)
        val savedSecret = tokens.prefs.getString("client_secret", null)
        if (savedId != null &&
                        savedSecret != null &&
                        savedId.isNotBlank() &&
                        savedSecret.isNotBlank()
        ) {
            return savedId to savedSecret
        }

        if (clientFile != null && clientFile.isFile) {
            runCatching {
                val o = JSONObject(clientFile.readText())
                val inner = o.optJSONObject("installed") ?: o.optJSONObject("web") ?: o
                val id = inner.optString("client_id")
                val secret = inner.optString("client_secret")
                if (id.isNotBlank() && secret.isNotBlank()) {
                    tokens.prefs
                            .edit()
                            .putString("client_id", id)
                            .putString("client_secret", secret)
                            .apply()
                    clientFile.delete()
                    return id to secret
                }
            }
        }
        return BuildConfig.OAUTH_CLIENT_ID to BuildConfig.OAUTH_CLIENT_SECRET
    }

    /** True once client credentials are available (pushed file or baked-in build). */
    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    /**
     * One end-to-end attempt. Binds the callback socket, hands the auth URL to [openBrowser], then
     * blocks (up to [timeoutMs]) waiting for Google's redirect. On success the refresh token is
     * exchanged and `token.json` is written; throws [IOException] otherwise.
     */
    @Throws(IOException::class)
    fun run(timeoutMs: Long = TimeUnit.MINUTES.toMillis(5), openBrowser: (Uri) -> Unit) {
        if (!isConfigured())
                throw IOException(
                        "No OAuth client configured — push client_secret.json or build with credentials."
                )
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 50, loopback).use { server ->
            server.soTimeout = timeoutMs.toInt()
            val port = server.localPort
            val redirectUri = "http://127.0.0.1:$port/"
            val state = randomToken()
            val authUrl = buildAuthUrl(redirectUri, state)
            openBrowser(Uri.parse(authUrl))

            val callback =
                    try {
                        acceptCallback(server)
                    } catch (e: java.net.SocketTimeoutException) {
                        throw IOException(
                                "Timed out waiting for sign-in. Tap Sign in to try again."
                        )
                    }
            callback.error?.let { throw IOException("Authorization denied: $it") }
            if (callback.state != state) {
                throw IOException("State mismatch — aborting sign-in.")
            }
            val code = callback.code ?: throw IOException("No authorization code returned.")

            val refreshToken = exchangeCode(code, redirectUri)
            writeTokenConfig(refreshToken)
        }
    }

    private fun buildAuthUrl(redirectUri: String, state: String): String =
            Uri.parse(authUri)
                    .buildUpon()
                    .appendQueryParameter("client_id", clientId)
                    .appendQueryParameter("redirect_uri", redirectUri)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("scope", scope)
                    .appendQueryParameter("access_type", "offline")
                    .appendQueryParameter("prompt", "consent")
                    .appendQueryParameter("state", state)
                    .build()
                    .toString()

    private data class Callback(val code: String?, val state: String?, val error: String?)

    private fun acceptCallback(server: ServerSocket): Callback {
        while (true) {
            val socket = server.accept()
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader()
                val requestLine =
                        reader.readLine().orEmpty() // e.g. "GET /?code=...&state=... HTTP/1.1"
                val path = requestLine.split(' ').getOrNull(1) ?: "/"
                val query = Uri.parse("http://127.0.0.1$path")
                val code = query.getQueryParameter("code")
                val state = query.getQueryParameter("state")
                val error = query.getQueryParameter("error")

                // Ignore spurious requests like /favicon.ico
                if (code == null && state == null && error == null) {
                    val ignored = "Ignored"
                    val resp =
                            "HTTP/1.1 200 OK\r\nContent-Length: ${ignored.length}\r\nConnection: close\r\n\r\n$ignored"
                    s.getOutputStream().apply {
                        write(resp.toByteArray())
                        flush()
                    }
                    return@use // continue loop
                }

                val body =
                        "<!doctype html><meta name=viewport content='width=device-width'>" +
                                "<body style='font-family:sans-serif;text-align:center;padding-top:3em'>" +
                                "<h1>Portal Photos</h1><p>Signed in. Return to the Portal Photos app.</p></body>"
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${body.toByteArray().size}\r\n")
                    append("Connection: close\r\n\r\n")
                    append(body)
                }
                s.getOutputStream().apply {
                    write(response.toByteArray())
                    flush()
                }
                return Callback(code, state, error)
            }
        }
    }

    private fun exchangeCode(code: String, redirectUri: String): String {
        val form =
                FormBody.Builder()
                        .add("code", code)
                        .add("client_id", clientId)
                        .add("client_secret", clientSecret)
                        .add("redirect_uri", redirectUri)
                        .add("grant_type", "authorization_code")
                        .build()
        val req = Request.Builder().url(tokenUri).post(form).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Token exchange failed ${resp.code}: $body")
            val o = JSONObject(body)
            return o.optString("refresh_token").ifBlank {
                throw IOException("No refresh_token returned — revoke prior access and retry.")
            }
        }
    }

    private fun writeTokenConfig(refreshToken: String) {
        tokens.saveConfig(
                TokenStore.Config(
                        clientId = clientId,
                        clientSecret = clientSecret,
                        refreshToken = refreshToken,
                        tokenUri = tokenUri
                )
        )
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or
                        android.util.Base64.NO_PADDING or
                        android.util.Base64.NO_WRAP
        )
    }
}
