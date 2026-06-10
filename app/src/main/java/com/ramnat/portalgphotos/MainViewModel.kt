package com.ramnat.portalgphotos

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import android.net.Uri
import com.ramnat.portalgphotos.data.AppSettings
import com.ramnat.portalgphotos.data.CachedItem
import com.ramnat.portalgphotos.data.Downloader
import com.ramnat.portalgphotos.data.MediaCache
import com.ramnat.portalgphotos.data.OnDeviceAuth
import com.ramnat.portalgphotos.data.PickerClient
import com.ramnat.portalgphotos.data.SettingsState
import com.ramnat.portalgphotos.data.SlideEffect
import com.ramnat.portalgphotos.data.TokenStore
import com.ramnat.portalgphotos.data.GeoPlace
import com.ramnat.portalgphotos.data.WeatherClient
import com.ramnat.portalgphotos.data.WeatherNow
import java.util.Locale
import com.ramnat.portalgphotos.ui.qrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job

sealed interface UiState {
    data object Loading : UiState
    data class NeedsSetup(val message: String, val canSignIn: Boolean) : UiState
    data object SigningIn : UiState
    data class Picking(val qr: ImageBitmap, val pickerUri: String, val status: String) : UiState
    data class Downloading(val done: Int, val total: Int) : UiState
    data class Showing(val items: List<CachedItem>) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val http = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseDir: File = app.getExternalFilesDir(null) ?: app.filesDir
    private val tokenFile = File(baseDir, "token.json")
    private val tokens = TokenStore(tokenFile, http)
    // OAuth client creds: a pushed client_secret.json (deploy a prebuilt APK without
    // rebuilding) or, failing that, the build-time BuildConfig values.
    private val auth = OnDeviceAuth(http, tokenFile, clientFile = File(baseDir, "client_secret.json"))
    private val picker = PickerClient(http, tokens)
    private val cacheDir = File(baseDir, "media").apply { mkdirs() }
    private val cache = MediaCache(cacheDir)
    private val downloader = Downloader(http, tokens, cacheDir)

    // Survives a trip to the browser / a process kill so an in-progress pick can resume.
    private val pendingFile = File(baseDir, "pending_session.json")

    private val settings = AppSettings(app)
    private val weather = WeatherClient(http)
    private val fahrenheit = Locale.getDefault().country == "US"

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(settings.load())
    val settingsState: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _weather = MutableStateFlow<WeatherNow?>(null)
    val weatherState: StateFlow<WeatherNow?> = _weather.asStateFlow()

    // Location search state for Settings: a status line + the candidate matches to pick from.
    private val _geoStatus = MutableStateFlow<String?>(null)
    val geoStatus: StateFlow<String?> = _geoStatus.asStateFlow()

    private val _geoResults = MutableStateFlow<List<GeoPlace>>(emptyList())
    val geoResults: StateFlow<List<GeoPlace>> = _geoResults.asStateFlow()

    private var pickerJob: Job? = null

    init { start(); weatherLoop() }

    fun setShuffle(v: Boolean) { settings.setShuffle(v); _settings.value = _settings.value.copy(shuffle = v) }
    fun setIntervalMs(v: Long) { settings.setIntervalMs(v); _settings.value = _settings.value.copy(intervalMs = v) }
    fun setEffect(v: SlideEffect) { settings.setEffect(v); _settings.value = _settings.value.copy(effect = v) }
    fun setMuteVideos(v: Boolean) { settings.setMuteVideos(v); _settings.value = _settings.value.copy(muteVideos = v) }
    fun setShowClock(v: Boolean) { settings.setShowClock(v); _settings.value = _settings.value.copy(showClock = v) }
    fun setShowPhotoDate(v: Boolean) { settings.setShowPhotoDate(v); _settings.value = _settings.value.copy(showPhotoDate = v) }
    fun setBackgroundStyle(v: com.ramnat.portalgphotos.data.BackgroundStyle) { settings.setBackgroundStyle(v); _settings.value = _settings.value.copy(backgroundStyle = v) }
    fun setSleepWhenAlone(v: Boolean) {
        settings.setSleepWhenAlone(v)
        _settings.value = _settings.value.copy(sleepWhenAlone = v)
        ScreensaverGuard.applyPowerPolicy(getApplication(), v)
    }

    fun setShowWeather(v: Boolean) {
        settings.setShowWeather(v)
        _settings.value = _settings.value.copy(showWeather = v)
        if (v) refreshWeather() else { _geoResults.value = emptyList(); _geoStatus.value = null }
    }

    /** Geocode a typed place name into candidate matches. One match auto-selects; several
     *  populate [geoResults] for the user to disambiguate via [chooseLocation]. */
    fun searchLocation(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _geoStatus.value = "Searching…"
            _geoResults.value = emptyList()
            val results = withContext(Dispatchers.IO) {
                runCatching { weather.geocode(query) }.getOrDefault(emptyList())
            }
            when {
                results.isEmpty() -> _geoStatus.value = "Couldn't find that place"
                results.size == 1 -> { _geoStatus.value = null; chooseLocation(results.first()) }
                else -> { _geoStatus.value = null; _geoResults.value = results }
            }
        }
    }

    /** Persist a chosen place and refresh the weather. */
    fun chooseLocation(place: GeoPlace) {
        settings.setWeatherLocation(place.lat, place.lon, place.label)
        _settings.value = _settings.value.copy(
            weatherLat = place.lat, weatherLon = place.lon, weatherPlace = place.label,
        )
        _geoResults.value = emptyList()
        _geoStatus.value = null
        refreshWeather()
    }

    private fun refreshWeather() {
        val s = _settings.value
        val lat = s.weatherLat
        val lon = s.weatherLon
        if (!s.showWeather || lat == null || lon == null) return
        viewModelScope.launch {
            val w = withContext(Dispatchers.IO) {
                runCatching { weather.current(lat, lon, fahrenheit) }.getOrNull()
            }
            if (w != null) _weather.value = w
        }
    }

    private fun weatherLoop() {
        viewModelScope.launch {
            while (true) {
                refreshWeather()
                delay(30 * 60 * 1000L)
            }
        }
    }

    /** Drop the given items from the cache (deleting their files) and update the slideshow. */
    fun removeItems(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                cache.load().filter { it.id !in ids }.also { cache.replaceAll(it) }
            }
            if (remaining.isEmpty()) runPicker(replace = true) // emptied the set — go pick again
            else _state.value = UiState.Showing(remaining)
        }
    }

    private data class Pending(val id: String, val replace: Boolean, val deadline: Long)

    private fun savePending(id: String, replace: Boolean, deadline: Long) {
        runCatching {
            pendingFile.writeText(
                JSONObject().put("id", id).put("replace", replace).put("deadline", deadline).toString()
            )
        }
    }

    private fun loadPending(): Pending? = runCatching {
        if (!pendingFile.exists()) return null
        val o = JSONObject(pendingFile.readText())
        Pending(o.getString("id"), o.getBoolean("replace"), o.getLong("deadline"))
    }.getOrNull()

    private fun clearPending() {
        runCatching { pendingFile.delete() }
    }

    /** Decide where to land: setup needed, resume a pending pick, show cached, or pick. */
    fun start() {
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            _state.value = UiState.Loading
            if (tokens.config() == null) {
                val pkg = getApplication<Application>().packageName
                _state.value = UiState.NeedsSetup(
                    message = if (auth.isConfigured())
                        "Sign in to your Google account to choose photos."
                    else
                        "Push your OAuth client to enable sign-in, then tap Retry:\n\n" +
                            "adb push client_secret.json /sdcard/Android/data/$pkg/files/client_secret.json\n\n" +
                            "(Or push a pre-made token.json to the same folder.)",
                    canSignIn = auth.isConfigured(),
                )
                return@launch
            }
            // If a pick was in progress (e.g. we went to the browser and got killed),
            // the selection is waiting server-side — finish it instead of dropping to cache.
            val pending = loadPending()
            if (pending != null && System.currentTimeMillis() < pending.deadline) {
                val s = withContext(Dispatchers.IO) {
                    runCatching { picker.getSession(pending.id) }.getOrNull()
                }
                if (s != null && s.mediaItemsSet) {
                    consumeSession(pending.id, pending.replace, pending.deadline)
                    return@launch
                }
            }
            clearPending() // expired, gone, or the user never finished — drop it
            val cached = withContext(Dispatchers.IO) { cache.load() }
            if (cached.isNotEmpty()) {
                _state.value = UiState.Showing(cached)
            } else {
                runPicker(replace = true)
            }
        }
    }

    /**
     * Run the loopback OAuth flow on the Portal itself: open the auth URL in the device's
     * signed-in browser, catch the redirect, and write token.json. Then proceed as normal.
     */
    fun signIn() {
        viewModelScope.launch {
            _state.value = UiState.SigningIn
            try {
                withContext(Dispatchers.IO) {
                    auth.run(openBrowser = { uri ->
                        val ctx = getApplication<Application>()
                        runCatching {
                            androidx.browser.customtabs.CustomTabsIntent.Builder()
                                .build()
                                .apply {
                                    // CustomTabsIntent defaults to launching in the current task. Since we are
                                    // using the Application context here, Android 10+ requires NEW_TASK.
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                .launchUrl(ctx, uri)
                        }
                    })
                }
                
                // OAuth is done. Yank the app back to the foreground to auto-close the Custom Tab.
                val ctx = getApplication<Application>()
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                ctx.startActivity(intent)
                
                start()
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Sign-in failed")
            }
        }
    }

    /** Pick more photos and append them to the current set. */
    fun addPhotos() {
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch { runPicker(replace = false) }
    }

    /** Pick a new set, replacing everything currently shown. */
    fun replacePhotos() {
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch { runPicker(replace = true) }
    }

    /** Abort a running picker flow and return to the slideshow (or setup if cache is empty). */
    fun cancelPicker() {
        pickerJob?.cancel()
        pickerJob = null
        clearPending()
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { cache.load() }
            if (cached.isNotEmpty()) _state.value = UiState.Showing(cached)
            else start()
        }
    }

    private suspend fun runPicker(replace: Boolean) {
        try {
            _state.value = UiState.Loading
            val session = withContext(Dispatchers.IO) { picker.createSession() }
            val deadline = System.currentTimeMillis() + session.timeoutMs
            savePending(session.id, replace, deadline)
            _state.value = UiState.Picking(
                qr = qrBitmap(session.pickerUri),
                pickerUri = session.pickerUri,
                status = "Waiting for your selection in Google Photos…",
            )
            consumeSession(session.id, replace, deadline)
        } catch (e: Exception) {
            clearPending()
            _state.value = UiState.Error(e.message ?: "Something went wrong")
        }
    }

    /** Poll the session until a selection lands, then download and cache it. */
    private suspend fun consumeSession(sessionId: String, replace: Boolean, deadline: Long) {
        try {
            val existing =
                if (replace) emptyList() else withContext(Dispatchers.IO) { cache.load() }
            var session = withContext(Dispatchers.IO) { picker.getSession(sessionId) }
            while (!session.mediaItemsSet) {
                if (System.currentTimeMillis() > deadline) {
                    clearPending()
                    _state.value = UiState.Error("Timed out waiting for a selection.")
                    return
                }
                delay(session.pollIntervalMs.coerceAtLeast(1500L))
                session = withContext(Dispatchers.IO) { picker.getSession(sessionId) }
            }

            // The user finished picking. The Portal browser often ignores the /autoclose
            // command, so we programmatically yank our app back to the foreground here.
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ctx.startActivity(intent)

            val items = withContext(Dispatchers.IO) { picker.listMediaItems(sessionId) }
            if (items.isEmpty()) {
                clearPending()
                _state.value = UiState.Error("No photos were selected.")
                return
            }
            // Deduplicate: skip items already in the cache so re-picking the same
            // photos in an "Add" flow doesn't waste bandwidth or disk space.
            val existingIds = existing.map { it.id }.toHashSet()
            val fresh = items.filter { it.id !in existingIds }
            if (fresh.isEmpty()) {
                // Every picked item was already cached — nothing new to download.
                clearPending()
                _state.value = UiState.Showing(existing)
                return
            }
            val batch = System.currentTimeMillis()
            val newItems = ArrayList<CachedItem>(fresh.size)
            fresh.forEachIndexed { i, item ->
                _state.value = UiState.Downloading(i, fresh.size)
                newItems.add(withContext(Dispatchers.IO) { downloader.download(item, "${batch}_$i") })
            }
            val finalItems = existing + newItems
            withContext(Dispatchers.IO) { cache.replaceAll(finalItems) }
            clearPending()
            _state.value = UiState.Showing(finalItems)
        } catch (e: Exception) {
            clearPending()
            _state.value = UiState.Error(e.message ?: "Something went wrong")
        }
    }

    // ── GPS location detection ────────────────────────────────────────────

    /** True when location permission is already granted. */
    fun hasLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Request a single location fix and use it as the weather location. */
    @Suppress("MissingPermission")
    fun detectLocation() {
        if (!hasLocationPermission()) {
            _geoStatus.value = "Location permission not granted"
            return
        }
        val ctx = getApplication<Application>()
        val lm = ctx.getSystemService(LocationManager::class.java) ?: run {
            _geoStatus.value = "Location services unavailable"
            return
        }
        _geoStatus.value = "Getting location…"
        _geoResults.value = emptyList()

        // Try cached last-known first — usually good enough for a stationary Portal.
        val last: Location? =
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (last != null) {
            applyDetectedLocation(last.latitude, last.longitude)
            return
        }

        // No cached fix — request a fresh one.
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> { _geoStatus.value = "No location provider available"; return }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lm.removeUpdates(this)
                applyDetectedLocation(loc.latitude, loc.longitude)
            }
            override fun onProviderDisabled(p: String) {
                lm.removeUpdates(this)
                _geoStatus.value = "Location provider disabled"
            }
            @Deprecated("Required on API <29", ReplaceWith(""))
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        @Suppress("DEPRECATION")
        lm.requestSingleUpdate(provider, listener, null)
    }

    private fun applyDetectedLocation(lat: Double, lon: Double) {
        viewModelScope.launch {
            // Reverse-geocode via Nominatim (OpenStreetMap) — free, keyless, no GMS needed.
            val label = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "https://nominatim.openstreetmap.org/reverse" +
                        "?lat=$lat&lon=$lon&format=json&zoom=10"
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "PortalGPhotos/1.0")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        val addr = JSONObject(resp.body?.string().orEmpty())
                            .optJSONObject("address") ?: return@runCatching null
                        listOfNotNull(
                            addr.optString("city").ifBlank { null }
                                ?: addr.optString("town").ifBlank { null }
                                ?: addr.optString("village").ifBlank { null },
                            addr.optString("state").ifBlank { null },
                            addr.optString("country_code").uppercase().ifBlank { null },
                        ).joinToString(", ").ifBlank { null }
                    }
                }.getOrNull()
            }
            val place = GeoPlace(lat, lon, label ?: "%.2f, %.2f".format(lat, lon))
            chooseLocation(place)
            _geoStatus.value = null
        }
    }
}
