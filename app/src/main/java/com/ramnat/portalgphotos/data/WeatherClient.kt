package com.ramnat.portalgphotos.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Current conditions for the slideshow overlay. [code] is a WMO weather code. */
data class WeatherNow(val temp: Int, val code: Int, val isDay: Boolean, val unit: String)

/** A geocoded place to anchor the weather lookup. */
data class GeoPlace(val lat: Double, val lon: Double, val label: String)

/**
 * Open-Meteo client — free, keyless, no GMS. Plain OkHttp + org.json, matching the rest of
 * the app's networking. One call for current conditions, one for geocoding a typed city.
 */
class WeatherClient(private val http: OkHttpClient) {

    @Throws(IOException::class)
    fun current(lat: Double, lon: Double, fahrenheit: Boolean): WeatherNow {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit"
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("weather ${resp.code}: $body")
            val cur = JSONObject(body).getJSONObject("current")
            return WeatherNow(
                temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                unit = if (fahrenheit) "F" else "C",
            )
        }
    }

    /**
     * Resolve a free-text place name to candidate coordinates, best match first. Open-Meteo
     * matches on the place name only (a "City, State" qualifier breaks it), so we search on
     * the part before any comma and return several matches for the caller to disambiguate.
     */
    fun geocode(query: String): List<GeoPlace> {
        val name = query.substringBefore(',').trim()
        if (name.isEmpty()) return emptyList()
        val q = URLEncoder.encode(name, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=5&language=en&format=json"
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val label = listOfNotNull(
                    o.optString("name").ifBlank { null },
                    o.optString("admin1").ifBlank { null },
                    o.optString("country_code").ifBlank { null },
                ).joinToString(", ")
                GeoPlace(o.getDouble("latitude"), o.getDouble("longitude"), label)
            }
        }
    }
}
