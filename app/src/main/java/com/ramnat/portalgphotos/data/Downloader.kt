package com.ramnat.portalgphotos.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import android.content.res.Resources
/**
 * Downloads picked media bytes. Picker baseUrls require the OAuth bearer token and
 * accept Google's sizing suffixes: "=w<W>-h<H>" for stills, "=dv" for video.
 */
class Downloader(
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val cacheDir: File,
) {

    /** [key] must be unique across picks so appended media never overwrites existing files. */
    @Throws(IOException::class)
    fun download(item: PickedItem, key: String): CachedItem {
        val metrics = Resources.getSystem().displayMetrics
        val maxRes = maxOf(metrics.widthPixels, metrics.heightPixels)
        val url = if (item.isVideoType()) "${item.baseUrl}=dv" else "${item.baseUrl}=w$maxRes-h$maxRes"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${tokens.accessToken()}")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("download failed ${resp.code} for ${item.filename}")
            }
            val ext = if (item.isVideoType()) "mp4" else "jpg"
            val out = File(cacheDir, "media_$key.$ext")
            val stream = resp.body?.byteStream() ?: throw IOException("empty response body")
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            return CachedItem(item.id, item.type, out, item.mimeType, item.width, item.height, item.createTime)
        }
    }
}

private fun PickedItem.isVideoType(): Boolean = type == "VIDEO"
