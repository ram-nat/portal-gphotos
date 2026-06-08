package com.ramnat.portalgphotos.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Client for the Google Photos Picker API (photospicker.googleapis.com/v1). */
class PickerClient(private val http: OkHttpClient, private val tokens: TokenStore) {

    private val base = "https://photospicker.googleapis.com/v1"

    data class Session(
        val id: String,
        val pickerUri: String,
        val pollIntervalMs: Long,
        val timeoutMs: Long,
        val mediaItemsSet: Boolean,
    )

    @Throws(IOException::class)
    fun createSession(): Session {
        val body = "{}".toRequestBody("application/json".toMediaType())
        val req = authed(Request.Builder().url("$base/sessions").post(body))
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("sessions.create failed ${resp.code}: $text")
            parseSession(JSONObject(text))
        }
    }

    @Throws(IOException::class)
    fun getSession(id: String): Session {
        val req = authed(Request.Builder().url("$base/sessions/$id").get())
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("sessions.get failed ${resp.code}: $text")
            parseSession(JSONObject(text))
        }
    }

    @Throws(IOException::class)
    fun listMediaItems(sessionId: String): List<PickedItem> {
        val out = ArrayList<PickedItem>()
        var pageToken: String? = null
        do {
            val urlBuilder = "$base/mediaItems".toHttpUrl().newBuilder()
                .addQueryParameter("sessionId", sessionId)
                .addQueryParameter("pageSize", "100")
            pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
            val req = authed(Request.Builder().url(urlBuilder.build()).get())
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("mediaItems.list failed ${resp.code}: $text")
                }
                val o = JSONObject(text)
                o.optJSONArray("mediaItems")?.let { arr ->
                    for (i in 0 until arr.length()) out.add(parseItem(arr.getJSONObject(i)))
                }
                pageToken = o.optString("nextPageToken").ifBlank { null }
            }
        } while (pageToken != null)
        return out
    }

    private fun authed(b: Request.Builder): Request =
        b.header("Authorization", "Bearer ${tokens.accessToken()}").build()

    private fun parseSession(o: JSONObject): Session {
        val pc = o.optJSONObject("pollingConfig")
        return Session(
            id = o.getString("id"),
            // Present on sessions.create (used for the QR); dropped from sessions.get
            // responses once the selection is made — so it must be optional here.
            pickerUri = o.optString("pickerUri", ""),
            pollIntervalMs = parseDurationMs(pc?.optString("pollInterval"), 3_000L),
            timeoutMs = parseDurationMs(pc?.optString("timeoutIn"), 1_800_000L),
            mediaItemsSet = o.optBoolean("mediaItemsSet", false),
        )
    }

    private fun parseItem(o: JSONObject): PickedItem {
        val mf = o.getJSONObject("mediaFile")
        val meta = mf.optJSONObject("mediaFileMetadata")
        return PickedItem(
            id = o.getString("id"),
            type = o.optString("type", "PHOTO"),
            baseUrl = mf.getString("baseUrl"),
            mimeType = mf.optString("mimeType", ""),
            filename = mf.optString("filename", ""),
            width = meta?.optInt("width", 0) ?: 0,
            height = meta?.optInt("height", 0) ?: 0,
            createTime = o.optString("createTime", ""),
        )
    }

    /** Protobuf Duration JSON is a string like "3s" or "1.500s". */
    private fun parseDurationMs(s: String?, default: Long): Long {
        if (s.isNullOrBlank()) return default
        return try {
            (s.trim().removeSuffix("s").toDouble() * 1000).toLong()
        } catch (e: Exception) {
            default
        }
    }
}
