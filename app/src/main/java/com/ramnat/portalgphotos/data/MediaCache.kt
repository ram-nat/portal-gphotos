package com.ramnat.portalgphotos.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk cache of downloaded media plus a JSON index. Picker baseUrls expire after
 * ~60 min and sessions are ephemeral, so we persist the actual bytes; the slideshow
 * then plays from here indefinitely and works offline.
 */
class MediaCache(private val dir: File) {

    private val indexFile = File(dir, "index.json")

    fun load(): List<CachedItem> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val f = File(o.getString("file"))
                if (!f.exists()) null
                else CachedItem(
                    id = o.getString("id"),
                    type = o.getString("type"),
                    file = f,
                    mimeType = o.optString("mimeType", ""),
                    width = o.optInt("width", 0),
                    height = o.optInt("height", 0),
                    createTime = o.optString("createTime", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Replace the cached set, deleting any files no longer referenced. */
    fun replaceAll(items: List<CachedItem>) {
        val keep = items.map { it.file.name }.toHashSet()
        dir.listFiles()?.forEach { f ->
            if (f.name != indexFile.name && f.name !in keep) f.delete()
        }
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("type", it.type)
                    put("file", it.file.absolutePath)
                    put("mimeType", it.mimeType)
                    put("width", it.width)
                    put("height", it.height)
                    put("createTime", it.createTime)
                }
            )
        }
        indexFile.writeText(arr.toString())
    }
}
