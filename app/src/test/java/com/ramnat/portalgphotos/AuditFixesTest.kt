package com.ramnat.portalgphotos

import android.app.Application
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.DisplayMetrics
import com.ramnat.portalgphotos.data.CachedItem
import com.ramnat.portalgphotos.data.Downloader
import com.ramnat.portalgphotos.data.MediaCache
import com.ramnat.portalgphotos.data.OnDeviceAuth
import com.ramnat.portalgphotos.data.PickedItem
import com.ramnat.portalgphotos.data.PickerClient
import com.ramnat.portalgphotos.data.TokenStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Automated JVM unit tests verifying the audit findings (no live network, no device required).
 * Each test fails against un-fixed HEAD and passes once its corresponding fix is applied.
 */
class AuditFixesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Fix 1: Slideshow pointerInput uses rememberUpdatedState ──────────────

    @Test
    fun fix1_slideshowPointerInputUsesRememberUpdatedStateToAvoidStaleSettingsClosure() {
        val ascii = screensBytecodeAscii()
        assertTrue(
            "SlideshowScreen should use rememberUpdatedState so toggling tapToDismiss/shuffle updates the active pointerInput closure",
            ascii.contains("rememberUpdatedState")
        )
    }

    // ── Fix 2: Newly pushed client_secret.json & token.json priority ──────────

    @Test
    fun fix2a_tokenStorePrioritizesNewlyPushedTokenFileOverSavedPrefs() {
        val tokenFile = File(tmp.root, "token.json")
        val prefs = InMemorySharedPreferences()
        val store = createTokenStore(prefs, tokenFile, OkHttpClient())

        // 1. Existing credentials already stored in prefs from an earlier setup
        store.saveConfig(
            TokenStore.Config(
                clientId = "old_client_id",
                clientSecret = "old_client_secret",
                refreshToken = "old_refresh_token",
                tokenUri = "https://oauth2.googleapis.com/token",
            )
        )

        // 2. User pushes a fresh token.json via deploy.sh
        tokenFile.writeText(
            JSONObject()
                .put("client_id", "new_client_id")
                .put("client_secret", "new_client_secret")
                .put("refresh_token", "new_refresh_token")
                .toString()
        )

        val cfg = store.config()
        assertNotNull(cfg)
        assertEquals("Newly pushed token.json should override saved client_id", "new_client_id", cfg!!.clientId)
        assertEquals("Newly pushed token.json should override saved refresh_token", "new_refresh_token", cfg.refreshToken)
        assertFalse("token.json should be ingested and deleted", tokenFile.exists())
    }

    @Test
    fun fix2b_onDeviceAuthPrioritizesNewlyPushedClientFileAndClearsStaleRefreshToken() {
        val clientFile = File(tmp.root, "client_secret.json")
        val prefs = InMemorySharedPreferences()
        val store = createTokenStore(prefs, File(tmp.root, "token.json"), OkHttpClient())

        // 1. Existing credentials already stored in prefs
        store.saveConfig(
            TokenStore.Config(
                clientId = "old_client_id",
                clientSecret = "old_client_secret",
                refreshToken = "old_refresh_token_bound_to_old_client",
                tokenUri = "https://oauth2.googleapis.com/token",
            )
        )

        // 2. User pushes a new client_secret.json (e.g. switched GCP project)
        clientFile.writeText(
            JSONObject().put(
                "installed",
                JSONObject()
                    .put("client_id", "new_client_id")
                    .put("client_secret", "new_client_secret")
            ).toString()
        )

        val auth = OnDeviceAuth(OkHttpClient(), store, clientFile)
        assertTrue(auth.isConfigured())
        assertEquals(
            "Newly pushed client_secret.json should overwrite old client_id in prefs",
            "new_client_id",
            prefs.getString("client_id", null)
        )
        assertFalse("client_secret.json should be deleted after ingestion", clientFile.exists())
        assertNull(
            "Switching to a new client_id should clear the old refresh_token bound to the previous client",
            prefs.getString("refresh_token", null)
        )
    }

    // ── Fix 3: Sign-in cancellation & Picker timeout / empty fallback ─────────

    @Test
    fun fix3a_onDeviceAuthSupportsImmediateCancelDuringLoopbackWait() {
        val prefs = InMemorySharedPreferences().apply {
            edit().putString("client_id", "cid").putString("client_secret", "csecret").apply()
        }
        val store = createTokenStore(prefs, File(tmp.root, "token.json"), OkHttpClient())
        val auth = OnDeviceAuth(OkHttpClient(), store, null)

        // Verify OnDeviceAuth exposes cancel() and unblocks run() immediately
        val cancelMethod = runCatching { OnDeviceAuth::class.java.getDeclaredMethod("cancel") }.getOrNull()
        assertNotNull("OnDeviceAuth should expose a cancel() method to close the loopback ServerSocket", cancelMethod)

        val browserOpened = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val thread = Thread {
            try {
                auth.run(timeoutMs = 10_000L, openBrowser = { browserOpened.countDown() })
            } catch (_: Throwable) {
                // Expected when socket is closed on cancel
            } finally {
                finished.countDown()
            }
        }.apply { isDaemon = true; start() }

        assertTrue("Browser callback should be invoked", browserOpened.await(2, TimeUnit.SECONDS))
        cancelMethod!!.invoke(auth)
        assertTrue(
            "auth.cancel() should unblock ServerSocket.accept() immediately (<1s), not wait for timeout",
            finished.await(1, TimeUnit.SECONDS)
        )
        thread.join(500)
    }

    @Test
    fun fix3b_pickerTimeoutFallsBackToExistingSlideshowWhenCacheIsNonEmpty() = runBlocking {
        val cacheDir = tmp.newFolder("media_timeout")
        val existingFile = File(cacheDir, "media_existing.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val existingItem = CachedItem("existing_1", "PHOTO", existingFile, "image/jpeg", 800, 600, "2025-01-01T00:00:00Z")
        val cache = MediaCache(cacheDir).apply { replaceAll(listOf(existingItem)) }

        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/sessions/sess_timeout") ->
                    jsonResponse(req, JSONObject().put("id", "sess_timeout").put("mediaItemsSet", false))
                else -> errorResponse(req, 404, "unexpected ${req.url}")
            }
        }

        val vm = createTestableViewModel(cacheDir, cache, http)
        // Session deadline has already passed while waiting for selection
        invokeConsumeSession(vm, "sess_timeout", replace = false, deadline = System.currentTimeMillis() - 1_000L)

        val state = vm.state.value
        assertTrue(
            "When cached photos exist, a picker timeout should fall back to UiState.Showing instead of stranding the frame on $state",
            state is UiState.Showing
        )
        assertEquals(listOf("existing_1"), (state as UiState.Showing).items.map { it.id })
    }

    @Test
    fun fix3c_emptyPickerSelectionFallsBackToExistingSlideshowWhenCacheIsNonEmpty() = runBlocking {
        val cacheDir = tmp.newFolder("media_empty_pick")
        val existingFile = File(cacheDir, "media_existing.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val existingItem = CachedItem("existing_1", "PHOTO", existingFile, "image/jpeg", 800, 600, "2025-01-01T00:00:00Z")
        val cache = MediaCache(cacheDir).apply { replaceAll(listOf(existingItem)) }

        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/sessions/sess_empty") ->
                    jsonResponse(req, JSONObject().put("id", "sess_empty").put("mediaItemsSet", true))
                req.url.encodedPath.endsWith("/mediaItems") ->
                    jsonResponse(req, JSONObject()) // empty mediaItems
                else -> errorResponse(req, 404, "unexpected ${req.url}")
            }
        }

        val vm = createTestableViewModel(cacheDir, cache, http)
        invokeConsumeSession(vm, "sess_empty", replace = false, deadline = System.currentTimeMillis() + 60_000L)

        val state = vm.state.value
        assertTrue(
            "When cached photos exist, an empty pick should fall back to UiState.Showing instead of $state",
            state is UiState.Showing
        )
        assertEquals(listOf("existing_1"), (state as UiState.Showing).items.map { it.id })
    }

    // ── Fix 4: Space-conscious, resumable batch downloads & timeout fix ───────

    @Test
    fun fix4a_downloaderDeletesPartialFileWhenStreamFailsMidDownload() {
        val cacheDir = tmp.newFolder("media_partial")
        val prefs = InMemorySharedPreferences()
        val store = createTokenStore(prefs, File(tmp.root, "token.json"), OkHttpClient()).apply {
            setField(this, "cachedToken", "valid_access_token")
            setField(this, "expiresAtMs", Long.MAX_VALUE)
        }

        // Simulate a response stream that writes 512 bytes and then drops the connection
        val failingHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val brokenBody = object : ResponseBody() {
                    override fun contentType() = "image/jpeg".toMediaType()
                    override fun contentLength() = 4096L
                    override fun source(): BufferedSource {
                        val buf = Buffer().write(ByteArray(512) { 0x42 })
                        return object : ForwardingSource(buf) {
                            private var readOnce = false
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                if (!readOnce) {
                                    readOnce = true
                                    return super.read(sink, byteCount)
                                }
                                throw IOException("Simulated Wi-Fi disconnect mid-stream")
                            }
                        }.buffer()
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(brokenHttpBody(brokenBody))
                    .build()
            })
            .build()

        val downloader = Downloader(failingHttp, store, cacheDir)
        val item = PickedItem(
            id = "p1",
            type = "PHOTO",
            baseUrl = "https://lh3.googleusercontent.com/fake/p1",
            mimeType = "image/jpeg",
            filename = "p1.jpg",
            width = 800,
            height = 600,
            createTime = "",
        )

        try {
            downloader.download(item, "batch_0")
            fail("Expected IOException from broken stream")
        } catch (_: IOException) {
            // Expected
        }

        val leftoverFiles = cacheDir.listFiles()?.toList().orEmpty()
        assertTrue(
            "Partial file should be deleted on stream failure, but found: ${leftoverFiles.map { it.name }}",
            leftoverFiles.isEmpty()
        )
    }

    @Test
    fun fix4b_httpClientDoesNotEnforceHard120SecondCallTimeoutOnVideoStreams() {
        val classBytes = MainViewModel::class.java.getResourceAsStream("MainViewModel.class")!!.use { it.readBytes() }
        val ascii = String(classBytes, Charsets.ISO_8859_1)
        assertFalse(
            "MainViewModel's OkHttpClient should not set a hard callTimeout (which kills active >2m video streams)",
            ascii.contains("callTimeout")
        )
        assertTrue(
            "MainViewModel's OkHttpClient should configure readTimeout for stalled connections",
            ascii.contains("readTimeout")
        )
    }

    @Test
    fun fix4c_replaceAllReusesCachedFilesAndPrunesDeselectedFilesBeforeDownloading() = runBlocking {
        val cacheDir = tmp.newFolder("media_replace")
        val oldRemovedFile = File(cacheDir, "media_old_removed.jpg").apply { writeBytes(byteArrayOf(1, 1, 1)) }
        val keptOverlapFile = File(cacheDir, "media_kept_overlap.jpg").apply { writeBytes(byteArrayOf(2, 2, 2)) }
        val cache = MediaCache(cacheDir).apply {
            replaceAll(
                listOf(
                    CachedItem("old_removed", "PHOTO", oldRemovedFile, "image/jpeg", 800, 600, ""),
                    CachedItem("kept_overlap", "PHOTO", keptOverlapFile, "image/jpeg", 800, 600, ""),
                )
            )
        }

        val overlapDownloadCalls = AtomicInteger(0)
        val oldRemovedWasPrunedBeforeNewDownload = AtomicBoolean(false)

        val http = mockHttp { req ->
            val url = req.url.toString()
            when {
                url.endsWith("/sessions/sess_replace") ->
                    jsonResponse(req, JSONObject().put("id", "sess_replace").put("mediaItemsSet", true))
                url.contains("/mediaItems") ->
                    jsonResponse(
                        req,
                        JSONObject().put(
                            "mediaItems",
                            JSONArray()
                                .put(pickerMediaItemJson("kept_overlap", "https://lh3.googleusercontent.com/kept_overlap"))
                                .put(pickerMediaItemJson("new_photo", "https://lh3.googleusercontent.com/new_photo"))
                        )
                    )
                url.startsWith("https://lh3.googleusercontent.com/kept_overlap") -> {
                    overlapDownloadCalls.incrementAndGet()
                    bytesResponse(req, byteArrayOf(2, 2, 2))
                }
                url.startsWith("https://lh3.googleusercontent.com/new_photo") -> {
                    // Check whether old_removed was already deleted BEFORE downloading new_photo!
                    oldRemovedWasPrunedBeforeNewDownload.set(!oldRemovedFile.exists())
                    bytesResponse(req, byteArrayOf(3, 3, 3))
                }
                else -> errorResponse(req, 404, "unexpected $url")
            }
        }

        val vm = createTestableViewModel(cacheDir, cache, http)
        invokeConsumeSession(vm, "sess_replace", replace = true, deadline = System.currentTimeMillis() + 60_000L)

        assertEquals(
            "Replace all should reuse already-cached overlapping items instead of re-downloading them",
            0,
            overlapDownloadCalls.get()
        )
        assertTrue(
            "Replace all should prune deselected old files BEFORE downloading new items to minimize peak disk usage",
            oldRemovedWasPrunedBeforeNewDownload.get()
        )
        assertEquals(listOf("kept_overlap", "new_photo"), cache.load().map { it.id })
    }

    @Test
    fun fix4d_partialBatchFailurePreservesCompletedDownloadsAndPendingSessionForRetry() = runBlocking {
        val cacheDir = tmp.newFolder("media_partial_batch")
        val cache = MediaCache(cacheDir)

        val http = mockHttp { req ->
            val url = req.url.toString()
            when {
                url.endsWith("/sessions/sess_batch") ->
                    jsonResponse(req, JSONObject().put("id", "sess_batch").put("mediaItemsSet", true))
                url.contains("/mediaItems") ->
                    jsonResponse(
                        req,
                        JSONObject().put(
                            "mediaItems",
                            JSONArray()
                                .put(pickerMediaItemJson("photo_1", "https://lh3.googleusercontent.com/photo_1"))
                                .put(pickerMediaItemJson("photo_2", "https://lh3.googleusercontent.com/photo_2"))
                        )
                    )
                url.startsWith("https://lh3.googleusercontent.com/photo_1") ->
                    bytesResponse(req, byteArrayOf(9, 9, 9))
                url.startsWith("https://lh3.googleusercontent.com/photo_2") ->
                    errorResponse(req, 500, "Transient server error on photo_2")
                else -> errorResponse(req, 404, "unexpected $url")
            }
        }

        val vm = createTestableViewModel(cacheDir, cache, http)
        val pendingFile = getField<File>(vm, "pendingFile")
        pendingFile.writeText(
            JSONObject()
                .put("id", "sess_batch")
                .put("replace", false)
                .put("deadline", System.currentTimeMillis() + 60_000L)
                .toString()
        )

        invokeConsumeSession(vm, "sess_batch", replace = false, deadline = System.currentTimeMillis() + 60_000L)

        assertEquals(
            "Completed downloads (photo_1) should be saved to index.json even when a later item in the batch fails",
            listOf("photo_1"),
            cache.load().map { it.id }
        )
        assertTrue(
            "pending_session.json should be preserved on transient download error so Retry can resume the remaining items",
            pendingFile.exists()
        )
    }

    // ── Fix 5: Cancel delayed FLAG_KEEP_SCREEN_ON clear job on policy toggle ──

    @Test
    fun fix5_mainActivityTracksPendingClearKeepScreenOnJobToPreventRace() {
        val jobField = runCatching {
            MainActivity::class.java.getDeclaredField("clearKeepScreenOnJob")
        }.getOrNull()
        assertNotNull(
            "MainActivity should track clearKeepScreenOnJob so rapid SLEEP_WHEN_ALONE -> AWAKE_FOREVER toggles cancel the 2s delayed clear",
            jobField
        )
    }

    // ── Fix 6: Video thumbnails & 320px grid sizing in ManageScreen ───────────

    @Test
    fun fix6_manageScreenExtractsVideoThumbnailsWithThumbnailUtils() {
        val ascii = screensBytecodeAscii()
        assertTrue(
            "ManageScreen should extract video thumbnails via ThumbnailUtils.createVideoThumbnail instead of showing a blank tile",
            ascii.contains("createVideoThumbnail")
        )
    }

    // ── Fix 7: Dynamic Ken Burns duration & next-slide preloading ─────────────

    @Test
    fun fix7_kenBurnsScalesWithIntervalAndSlideshowPreloadsNextImage() {
        val screensKt = Class.forName("com.ramnat.portalgphotos.ui.ScreensKt")
        val kenBurnsAcceptsInterval = screensKt.declaredMethods.any { m ->
            m.name == "KenBurnsImage" && m.parameterTypes.any { it == Long::class.javaPrimitiveType }
        }
        assertTrue(
            "KenBurnsImage should accept intervalMs: Long so pan/zoom duration scales with the user's slide interval",
            kenBurnsAcceptsInterval
        )
        assertTrue(
            "SlideshowScreen should preload the next still image via imageLoader.enqueue",
            screensBytecodeAscii().contains("enqueue")
        )
    }

    // ── Fix 8: Persist temperature unit (°F / °C) in AppSettings ──────────────

    @Test
    fun fix8_appSettingsPersistsTemperatureUnitAndDefaultsToFahrenheit() {
        val prefs = InMemorySharedPreferences()
        val appSettings = allocateInstance(com.ramnat.portalgphotos.data.AppSettings::class.java)
        setField(appSettings, "prefs", prefs)

        val defaultState = appSettings.load()
        val useFahrenheitProp = runCatching {
            defaultState.javaClass.getDeclaredField("useFahrenheit").apply { isAccessible = true }
        }.getOrNull()
        assertNotNull("SettingsState should include useFahrenheit property", useFahrenheitProp)
        assertEquals("useFahrenheit should default to true", true, useFahrenheitProp!!.get(defaultState))

        val setter = runCatching {
            com.ramnat.portalgphotos.data.AppSettings::class.java.getDeclaredMethod("setUseFahrenheit", Boolean::class.javaPrimitiveType)
        }.getOrNull()
        assertNotNull("AppSettings should expose setUseFahrenheit(Boolean)", setter)
        setter!!.invoke(appSettings, false)

        val updatedState = appSettings.load()
        assertEquals("useFahrenheit=false should persist across AppSettings.load()", false, useFahrenheitProp.get(updatedState))
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun screensBytecodeAscii(): String {
        val loader = MainViewModel::class.java.classLoader!!
        val sb = StringBuilder()
        val resUrl = loader.getResource("com/ramnat/portalgphotos/ui/ScreensKt.class") ?: return ""
        if (resUrl.protocol == "file") {
            File(resUrl.toURI()).parentFile?.listFiles()
                ?.filter { it.name.startsWith("ScreensKt") && it.name.endsWith(".class") }
                ?.forEach { sb.append(String(it.readBytes(), Charsets.ISO_8859_1)) }
        } else if (resUrl.protocol == "jar") {
            val conn = resUrl.openConnection() as java.net.JarURLConnection
            val jar = conn.jarFile
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.name.startsWith("com/ramnat/portalgphotos/ui/ScreensKt") && e.name.endsWith(".class")) {
                    jar.getInputStream(e).use { sb.append(String(it.readBytes(), Charsets.ISO_8859_1)) }
                }
            }
        }
        return sb.toString()
    }

    private fun brokenHttpBody(body: ResponseBody): ResponseBody = body

    private fun createTokenStore(prefs: SharedPreferences, configFile: File, http: OkHttpClient): TokenStore {
        val store = allocateInstance(TokenStore::class.java)
        setField(store, "configFile", configFile)
        setField(store, "http", http)
        setField(store, "prefs", prefs)
        setField(store, "cachedToken", null)
        setField(store, "expiresAtMs", 0L)
        return store
    }

    private fun createTestableViewModel(
        cacheDir: File,
        cache: MediaCache,
        customHttp: OkHttpClient?,
    ): MainViewModel {
        val vm = allocateInstance(MainViewModel::class.java)
        val app = Application()
        runCatching { setField(vm, "application", app) }

        val http = customHttp ?: OkHttpClient()

        val baseDir = tmp.newFolder()
        val tokenFile = File(baseDir, "token.json")
        val prefs = InMemorySharedPreferences()
        val tokens = createTokenStore(prefs, tokenFile, http).apply {
            setField(this, "cachedToken", "test_access_token")
            setField(this, "expiresAtMs", Long.MAX_VALUE)
        }

        val stateFlow = MutableStateFlow<UiState>(UiState.Loading)
        setField(vm, "http", http)
        setField(vm, "baseDir", baseDir)
        setField(vm, "tokenFile", tokenFile)
        setField(vm, "tokens", tokens)
        setField(vm, "auth", OnDeviceAuth(http, tokens, File(baseDir, "client_secret.json")))
        setField(vm, "picker", PickerClient(http, tokens))
        setField(vm, "cacheDir", cacheDir)
        setField(vm, "cache", cache)
        setField(vm, "downloader", Downloader(http, tokens, cacheDir))
        setField(vm, "pendingFile", File(baseDir, "pending_session.json"))
        setField(vm, "_state", stateFlow)
        setField(vm, "state", stateFlow.asStateFlow())
        return vm
    }

    private suspend fun invokeConsumeSession(vm: MainViewModel, sessionId: String, replace: Boolean, deadline: Long) {
        val method = MainViewModel::class.java.getDeclaredMethod(
            "consumeSession",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java,
        ).apply { isAccessible = true }
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            method.invoke(vm, sessionId, replace, deadline, cont)
        }
    }

    private fun pickerMediaItemJson(id: String, baseUrl: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "PHOTO")
            .put("createTime", "2025-05-01T00:00:00Z")
            .put(
                "mediaFile",
                JSONObject()
                    .put("baseUrl", baseUrl)
                    .put("mimeType", "image/jpeg")
                    .put("filename", "$id.jpg")
                    .put("mediaFileMetadata", JSONObject().put("width", 800).put("height", 600))
            )

    private fun mockHttp(handler: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun jsonResponse(req: okhttp3.Request, json: JSONObject): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toString().toResponseBody("application/json".toMediaType()))
            .build()

    private fun bytesResponse(req: okhttp3.Request, bytes: ByteArray): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody("image/jpeg".toMediaType()))
            .build()

    private fun errorResponse(req: okhttp3.Request, code: Int, msg: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(msg)
            .body(msg.toResponseBody("text/plain".toMediaType()))
            .build()

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateInstance(cls: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, cls) as T
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            cls = cls.superclass
        }
        throw NoSuchFieldException("$name not found on ${target.javaClass}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, name: String): T {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field.get(target) as T
            }
            cls = cls.superclass
        }
        throw NoSuchFieldException("$name not found on ${target.javaClass}")
    }

    /** Minimal in-memory SharedPreferences implementation for fast JVM tests. */
    private class InMemorySharedPreferences : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }
}
