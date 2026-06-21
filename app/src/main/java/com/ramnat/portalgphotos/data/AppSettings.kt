package com.ramnat.portalgphotos.data

import android.content.Context

/** Slideshow style: NONE = hard cut, FADE = crossfade, SLIDE = horizontal slide, all
 *  static; KEN_BURNS = crossfade with a slow pan/zoom. An enum so adding styles later
 *  doesn't break the persisted value. */
enum class SlideEffect { NONE, FADE, SLIDE, KEN_BURNS }

/** Background style for images that don't fit the aspect ratio perfectly. */
enum class BackgroundStyle { BLUR, BLACK, COLOR }

/** All user-facing settings, persisted via [AppSettings]. */
data class SettingsState(
    val shuffle: Boolean = false,
    val intervalMs: Long = 8_000L,
    val effect: SlideEffect = SlideEffect.KEN_BURNS,
    val muteVideos: Boolean = true,
    val showClock: Boolean = true,
    val showPhotoDate: Boolean = true,
    val showWeather: Boolean = false,
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    val weatherPlace: String? = null,
    // When on, the frame stops holding the screen awake, handing the sleep-vs-show
    // decision back to Portal's presence policy: someone there keeps it lit, an empty
    // room lets it sleep. Off = a permanent always-on frame.
    val sleepWhenAlone: Boolean = false,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.BLUR,
    val tapToDismiss: Boolean = true,
)

/** Thin typed wrapper over SharedPreferences. The single home for app settings. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): SettingsState {
        val place = prefs.getString(KEY_PLACE, null)?.ifBlank { null }
        val lat = if (place != null) prefs.getFloat(KEY_LAT, Float.NaN).toDouble().takeIf { !it.isNaN() } else null
        val lon = if (place != null) prefs.getFloat(KEY_LON, Float.NaN).toDouble().takeIf { !it.isNaN() } else null
        return SettingsState(
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            intervalMs = prefs.getLong(KEY_INTERVAL, 8_000L),
            effect = runCatching {
                SlideEffect.valueOf(prefs.getString(KEY_EFFECT, null) ?: SlideEffect.KEN_BURNS.name)
            }.getOrDefault(SlideEffect.KEN_BURNS),
            muteVideos = prefs.getBoolean(KEY_MUTE, true),
            showClock = prefs.getBoolean(KEY_CLOCK, true),
            showPhotoDate = prefs.getBoolean(KEY_PHOTO_DATE, true),
            showWeather = prefs.getBoolean(KEY_WEATHER, false),
            weatherLat = lat,
            weatherLon = lon,
            weatherPlace = place,
            sleepWhenAlone = prefs.getBoolean(KEY_SLEEP_ALONE, false),
            backgroundStyle = runCatching {
                BackgroundStyle.valueOf(prefs.getString(KEY_BG_STYLE, null) ?: BackgroundStyle.BLUR.name)
            }.getOrDefault(BackgroundStyle.BLUR),
            tapToDismiss = prefs.getBoolean(KEY_TAP_DISMISS, true),
        )
    }

    fun setShuffle(v: Boolean) = prefs.edit().putBoolean(KEY_SHUFFLE, v).apply()
    fun setIntervalMs(v: Long) = prefs.edit().putLong(KEY_INTERVAL, v).apply()
    fun setEffect(v: SlideEffect) = prefs.edit().putString(KEY_EFFECT, v.name).apply()
    fun setMuteVideos(v: Boolean) = prefs.edit().putBoolean(KEY_MUTE, v).apply()
    fun setShowClock(v: Boolean) = prefs.edit().putBoolean(KEY_CLOCK, v).apply()
    fun setShowPhotoDate(v: Boolean) = prefs.edit().putBoolean(KEY_PHOTO_DATE, v).apply()
    fun setShowWeather(v: Boolean) = prefs.edit().putBoolean(KEY_WEATHER, v).apply()
    fun setSleepWhenAlone(v: Boolean) = prefs.edit().putBoolean(KEY_SLEEP_ALONE, v).apply()
    fun setBackgroundStyle(v: BackgroundStyle) = prefs.edit().putString(KEY_BG_STYLE, v.name).apply()
    fun setTapToDismiss(v: Boolean) = prefs.edit().putBoolean(KEY_TAP_DISMISS, v).apply()
    fun setWeatherLocation(lat: Double, lon: Double, label: String) =
        prefs.edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LON, lon.toFloat())
            .putString(KEY_PLACE, label)
            .apply()

    private companion object {
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_INTERVAL = "interval_ms"
        const val KEY_EFFECT = "effect"
        const val KEY_MUTE = "mute_videos"
        const val KEY_CLOCK = "show_clock"
        const val KEY_PHOTO_DATE = "show_photo_date"
        const val KEY_WEATHER = "show_weather"
        const val KEY_SLEEP_ALONE = "sleep_when_alone"
        const val KEY_LAT = "weather_lat"
        const val KEY_LON = "weather_lon"
        const val KEY_PLACE = "weather_place"
        const val KEY_BG_STYLE = "bg_style"
        const val KEY_TAP_DISMISS = "tap_to_dismiss"
    }
}
