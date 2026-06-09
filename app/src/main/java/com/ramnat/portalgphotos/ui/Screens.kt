package com.ramnat.portalgphotos.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ramnat.portalgphotos.R
import com.ramnat.portalgphotos.UiState
import com.ramnat.portalgphotos.data.CachedItem
import com.ramnat.portalgphotos.data.GeoPlace
import com.ramnat.portalgphotos.data.SettingsState
import com.ramnat.portalgphotos.data.SlideEffect
import com.ramnat.portalgphotos.data.WeatherNow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Top-level renderer that maps app state to a screen. */
@Composable
fun AppRoot(
    state: UiState,
    settings: SettingsState,
    weather: WeatherNow?,
    geoStatus: String?,
    geoResults: List<GeoPlace>,
    onAdd: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    onSetInterval: (Long) -> Unit,
    onSetEffect: (SlideEffect) -> Unit,
    onSetMute: (Boolean) -> Unit,
    onSetShowClock: (Boolean) -> Unit,
    onSetShowPhotoDate: (Boolean) -> Unit,
    onSetShowWeather: (Boolean) -> Unit,
    onSetSleepWhenAlone: (Boolean) -> Unit,
    onSearchLocation: (String) -> Unit,
    onChooseLocation: (GeoPlace) -> Unit,
    onDetectLocation: () -> Unit,
    onRemoveItems: (Set<String>) -> Unit,
    gesturesEnabled: Boolean = true,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        when (state) {
            is UiState.Loading -> CenterMessage("Loading…")
            is UiState.NeedsSetup -> SetupScreen(state.message, state.canSignIn, onSignIn, onRetry)
            is UiState.SigningIn -> CenterMessage("Opening sign-in…\nApprove access, then return to this app.")
            is UiState.Picking -> PickingScreen(state.qr, state.pickerUri, state.status, onCancel)
            is UiState.Downloading ->
                CenterMessage("Downloading ${state.done + 1} of ${state.total}…")
            is UiState.Showing -> SlideshowScreen(
                items = state.items,
                settings = settings,
                weather = weather,
                geoStatus = geoStatus,
                geoResults = geoResults,
                onAdd = onAdd,
                onReplace = onReplace,
                onSetShuffle = onSetShuffle,
                onSetInterval = onSetInterval,
                onSetEffect = onSetEffect,
                onSetMute = onSetMute,
                onSetShowClock = onSetShowClock,
                onSetShowPhotoDate = onSetShowPhotoDate,
                onSetShowWeather = onSetShowWeather,
                onSetSleepWhenAlone = onSetSleepWhenAlone,
                onSearchLocation = onSearchLocation,
                onChooseLocation = onChooseLocation,
                onDetectLocation = onDetectLocation,
                onRemoveItems = onRemoveItems,
                gesturesEnabled = gesturesEnabled,
            )
            is UiState.Error -> ErrorScreen(state.message, onRetry)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(
        Modifier.fillMaxSize().padding(top = 64.dp, start = 48.dp, end = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 28.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SetupScreen(message: String, canSignIn: Boolean, onSignIn: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = 64.dp, start = 48.dp, end = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (canSignIn) "Welcome" else "Setup needed", color = Color.White, fontSize = 40.sp)
        Spacer(Modifier.height(24.dp))
        Text(message, color = Color.LightGray, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        if (canSignIn) {
            Button(onClick = onSignIn, modifier = Modifier.height(80.dp).width(360.dp)) {
                Text("Sign in on this device", fontSize = 24.sp)
            }
        } else {
            Button(onClick = onRetry, modifier = Modifier.height(72.dp)) {
                Text("Retry", fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun PickingScreen(qr: ImageBitmap, pickerUri: String, status: String, onCancel: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Choose photos", color = Color.White, fontSize = 32.sp)
        Spacer(Modifier.height(24.dp))
        // Open the Google Photos picker in the Portal's own browser (already signed in),
        // so you can pick right here without a second device. Polling continues in the
        // background, so the slideshow advances once a selection lands.
        Button(
            onClick = {
                val autocloseUri = "$pickerUri/autoclose"
                runCatching {
                    androidx.browser.customtabs.CustomTabsIntent.Builder()
                        .build()
                        .launchUrl(context, Uri.parse(autocloseUri))
                }
            },
            modifier = Modifier.height(80.dp).width(360.dp),
        ) {
            Text("Pick on this device", fontSize = 24.sp)
        }
        Spacer(Modifier.height(28.dp))
        Text("or scan with your phone", color = Color.LightGray, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Image(
            bitmap = qr,
            contentDescription = "Picker QR code",
            modifier = Modifier.size(260.dp).background(Color.White).padding(12.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(status, color = Color.LightGray, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel, modifier = Modifier.height(56.dp)) {
            Text("Cancel", fontSize = 20.sp, color = Color.LightGray)
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = 64.dp, start = 48.dp, end = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Something went wrong", color = Color.White, fontSize = 32.sp)
        Spacer(Modifier.height(16.dp))
        Text(message, color = Color.LightGray, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRetry, modifier = Modifier.height(72.dp)) {
            Text("Retry", fontSize = 24.sp)
        }
    }
}

private const val KEN_BURNS_MS = 9_000
private const val KEN_BURNS_MAX_SCALE = 1.06f

/** Which overlay sits over the slideshow. The slideshow stays composed underneath so its
 *  playback position (and thus "keep current photo") survives opening a submenu. */
private enum class Overlay { NONE, MENU, SETTINGS, MANAGE }

/** A shuffled permutation of [ids] whose first element isn't [avoidFirst] (so a reshuffle
 *  on wrap never immediately repeats the item we just showed). */
private fun shuffledIds(ids: List<String>, avoidFirst: String?): List<String> {
    if (ids.size <= 1) return ids
    var out = ids.shuffled()
    if (avoidFirst != null && out.first() == avoidFirst) out = out.drop(1) + out.first()
    return out
}

@Composable
private fun SlideshowScreen(
    items: List<CachedItem>,
    settings: SettingsState,
    weather: WeatherNow?,
    geoStatus: String?,
    geoResults: List<GeoPlace>,
    onAdd: () -> Unit,
    onReplace: () -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    onSetInterval: (Long) -> Unit,
    onSetEffect: (SlideEffect) -> Unit,
    onSetMute: (Boolean) -> Unit,
    onSetShowClock: (Boolean) -> Unit,
    onSetShowPhotoDate: (Boolean) -> Unit,
    onSetShowWeather: (Boolean) -> Unit,
    onSetSleepWhenAlone: (Boolean) -> Unit,
    onSearchLocation: (String) -> Unit,
    onChooseLocation: (GeoPlace) -> Unit,
    onDetectLocation: () -> Unit,
    onRemoveItems: (Set<String>) -> Unit,
    gesturesEnabled: Boolean,
) {
    if (items.isEmpty()) {
        CenterMessage("No photos to show")
        return
    }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    // Play order is a list of item ids (resilient to the set changing under us); `currentId`
    // is the item on screen. Tracking ids, not indices, keeps the current photo pinned when
    // the set or shuffle changes.
    var order by remember { mutableStateOf(items.map { it.id }) }
    var pos by remember { mutableIntStateOf(0) }
    var currentId by remember { mutableStateOf(items.first().id) }

    // Rebuild the order when the set or shuffle setting changes, pinning the current photo.
    LaunchedEffect(items, settings.shuffle) {
        val ids = items.map { it.id }
        val keep = currentId.takeIf { ids.contains(it) }
        order = if (settings.shuffle) shuffledIds(ids, null) else ids
        pos = keep?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        currentId = order.getOrElse(pos) { order.first() }
    }

    fun advance(step: Int) {
        if (order.isEmpty()) return
        var p = pos + step
        if (p >= order.size) {
            if (settings.shuffle) order = shuffledIds(items.map { it.id }, currentId)
            p = 0
        } else if (p < 0) {
            p = order.size - 1
        }
        pos = p
        currentId = order.getOrElse(p) { order.first() }
    }

    val interactive = overlay == Overlay.NONE

    // Only attach touch handlers in the interactive app. In screensaver (Dream) mode we
    // must NOT consume touches, so they fall through and the Dream dismisses on touch.
    val gestures = if (!gesturesEnabled) Modifier else Modifier
        .pointerInput(items.size) {
            detectTapGestures(
                onLongPress = { if (interactive) overlay = Overlay.MENU },
                // Tap left half = previous, right half = next. No on-screen controls.
                onTap = { offset -> if (interactive) { if (offset.x < size.width / 2f) advance(-1) else advance(1) } },
            )
        }
        .pointerInput(items.size) {
            val threshold = 64.dp.toPx()
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onHorizontalDrag = { _, amount -> total += amount },
                // Swipe left (negative) = next, swipe right (positive) = previous.
                onDragEnd = {
                    if (interactive) { if (total <= -threshold) advance(1) else if (total >= threshold) advance(-1) }
                },
            )
        }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(gestures),
    ) {
        val renderItem: @Composable (String) -> Unit = { id ->
            val item = items.firstOrNull { it.id == id } ?: items.first()
            if (item.isVideo) {
                VideoPlayer(item.file, playing = interactive, muted = settings.muteVideos, onEnded = { advance(1) })
            } else {
                BlurredFillPhoto(item.file, settings.effect)
            }
        }
        // The effect drives the transition between photos: hard cut, crossfade, or slide.
        when (settings.effect) {
            SlideEffect.NONE -> renderItem(currentId)
            SlideEffect.SLIDE -> AnimatedContent(
                targetState = currentId,
                transitionSpec = {
                    (slideInHorizontally(tween(600)) { it } + fadeIn(tween(600))) togetherWith
                        (slideOutHorizontally(tween(600)) { -it } + fadeOut(tween(600)))
                },
                label = "slide",
            ) { renderItem(it) }
            else -> Crossfade(targetState = currentId, animationSpec = tween(900), label = "fade") {
                renderItem(it)
            }
        }
        if (overlay == Overlay.NONE && (settings.showClock || settings.showWeather)) {
            SlideshowInfo(
                showClock = settings.showClock,
                showWeather = settings.showWeather,
                weather = weather,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 40.dp, bottom = 32.dp),
            )
        }
        if (overlay == Overlay.NONE && settings.showPhotoDate) {
            val current = items.firstOrNull { it.id == currentId } ?: items.first()
            PhotoDateCaption(
                createTime = current.createTime,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.dp, bottom = 32.dp),
            )
        }
        when (overlay) {
            Overlay.MENU -> SlideshowMenu(
                onDismiss = { overlay = Overlay.NONE },
                onAdd = { overlay = Overlay.NONE; onAdd() },
                onReplace = { overlay = Overlay.NONE; onReplace() },
                onManage = { overlay = Overlay.MANAGE },
                onSettings = { overlay = Overlay.SETTINGS },
            )
            Overlay.SETTINGS -> SettingsScreen(
                settings = settings,
                geoStatus = geoStatus,
                geoResults = geoResults,
                onSetShuffle = onSetShuffle,
                onSetInterval = onSetInterval,
                onSetEffect = onSetEffect,
                onSetMute = onSetMute,
                onSetShowClock = onSetShowClock,
                onSetShowPhotoDate = onSetShowPhotoDate,
                onSetShowWeather = onSetShowWeather,
                onSetSleepWhenAlone = onSetSleepWhenAlone,
                onSearchLocation = onSearchLocation,
                onChooseLocation = onChooseLocation,
                onDetectLocation = onDetectLocation,
                onClose = { overlay = Overlay.NONE },
            )
            Overlay.MANAGE -> ManageScreen(
                items = items,
                onRemove = onRemoveItems,
                onClose = { overlay = Overlay.NONE },
            )
            Overlay.NONE -> {}
        }
    }

    // Auto-advance stills after the configured interval; pause while an overlay is open.
    LaunchedEffect(currentId, items, interactive, settings.intervalMs) {
        if (!interactive) return@LaunchedEffect
        val item = items.firstOrNull { it.id == currentId } ?: return@LaunchedEffect
        if (!item.isVideo) {
            kotlinx.coroutines.delay(settings.intervalMs)
            advance(1)
        }
    }
}

/** RFC3339 createTime → "June 2024", or null if absent/unparseable. */
private fun formatPhotoDate(createTime: String): String? {
    if (createTime.isBlank()) return null
    return runCatching {
        java.time.Instant.parse(createTime)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }.getOrNull()
}

/** The current photo's capture month/year, subtly in the corner. Hidden when unknown. */
@Composable
private fun PhotoDateCaption(createTime: String, modifier: Modifier) {
    val label = remember(createTime) { formatPhotoDate(createTime) } ?: return
    Text(
        label,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 20.sp,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 12f)),
        modifier = modifier,
    )
}

/** Weather code (WMO) + day/night → one of our vector glyphs. */
private fun weatherIcon(code: Int, isDay: Boolean): Int = when (code) {
    0 -> if (isDay) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night
    1, 2 -> R.drawable.ic_weather_partly_cloudy
    3 -> R.drawable.ic_weather_cloudy
    45, 48 -> R.drawable.ic_weather_fog
    in 51..57, in 61..67, in 80..82 -> R.drawable.ic_weather_rain
    in 71..77, 85, 86 -> R.drawable.ic_weather_snow
    95, 96, 99 -> R.drawable.ic_weather_thunder
    else -> R.drawable.ic_weather_cloudy
}

/** Weather (optional) over the clock (optional) in the slideshow corner. Soft shadow keeps
 *  it legible over any photo. Ticks every 10 s — minute-accurate without burning cycles. */
@Composable
private fun SlideshowInfo(
    showClock: Boolean,
    showWeather: Boolean,
    weather: WeatherNow?,
    modifier: Modifier,
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            kotlinx.coroutines.delay(10_000)
        }
    }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 2f), blurRadius = 12f)
    Column(modifier) {
        if (showWeather && weather != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(weatherIcon(weather.code, weather.isDay)),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${weather.temp}°",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    style = TextStyle(shadow = shadow),
                )
            }
            if (showClock) Spacer(Modifier.height(8.dp))
        }
        if (showClock) {
            Text(
                timeFmt.format(now),
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                style = TextStyle(shadow = shadow),
            )
            Text(
                dateFmt.format(now),
                color = Color.White,
                fontSize = 22.sp,
                style = TextStyle(shadow = shadow),
            )
        }
    }
}

/** Long-press hub over the slideshow: change the set, manage downloads, or open settings. */
@Composable
private fun SlideshowMenu(
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onReplace: () -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Photos", color = Color.White, fontSize = 32.sp)
            Button(onClick = onAdd, modifier = Modifier.height(72.dp).width(320.dp)) {
                Text("Add photos", fontSize = 24.sp)
            }
            Button(onClick = onReplace, modifier = Modifier.height(72.dp).width(320.dp)) {
                Text("Replace all", fontSize = 24.sp)
            }
            Button(onClick = onManage, modifier = Modifier.height(72.dp).width(320.dp)) {
                Text("Manage media", fontSize = 24.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSettings, modifier = Modifier.height(72.dp).width(320.dp)) {
                Text("Settings", fontSize = 24.sp)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.height(56.dp)) {
                Text("Cancel", fontSize = 20.sp, color = Color.LightGray)
            }
        }
    }
}

private val CardColor = Color(0xFF1C1C1E)
private val ChipUnselected = Color(0xFF2C2C2E)
private val SubtitleColor = Color(0xFF9E9E9E)

/** Full-screen settings overlay: shuffle, slide interval, and still effect. Laid out as
 *  cards in a centered, reading-width column so controls sit near their labels. */
@Composable
private fun SettingsScreen(
    settings: SettingsState,
    geoStatus: String?,
    geoResults: List<GeoPlace>,
    onSetShuffle: (Boolean) -> Unit,
    onSetInterval: (Long) -> Unit,
    onSetEffect: (SlideEffect) -> Unit,
    onSetMute: (Boolean) -> Unit,
    onSetShowClock: (Boolean) -> Unit,
    onSetShowPhotoDate: (Boolean) -> Unit,
    onSetShowWeather: (Boolean) -> Unit,
    onSetSleepWhenAlone: (Boolean) -> Unit,
    onSearchLocation: (String) -> Unit,
    onChooseLocation: (GeoPlace) -> Unit,
    onDetectLocation: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(top = 64.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = Color.White, fontSize = 40.sp)
                TextButton(onClick = onClose, modifier = Modifier.height(56.dp)) {
                    Text("Done", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
                }
            }

            ToggleCard("Shuffle", "Play photos in random order", settings.shuffle, onSetShuffle)

            SettingCard {
                Text("Slide interval", color = Color.White, fontSize = 24.sp)
                Text("How long each photo stays on screen", color = SubtitleColor, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                ChipRow(
                    options = listOf("5s" to 5_000L, "8s" to 8_000L, "15s" to 15_000L, "30s" to 30_000L, "1m" to 60_000L),
                    selected = settings.intervalMs,
                    onSelect = onSetInterval,
                )
            }

            SettingCard {
                Text("Effect", color = Color.White, fontSize = 24.sp)
                Text("How photos transition and move", color = SubtitleColor, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                ChipRow(
                    options = listOf(
                        "None" to SlideEffect.NONE,
                        "Fade" to SlideEffect.FADE,
                        "Slide" to SlideEffect.SLIDE,
                        "Ken Burns" to SlideEffect.KEN_BURNS,
                    ),
                    selected = settings.effect,
                    onSelect = onSetEffect,
                )
            }

            ToggleCard("Mute videos", "Silence audio on video clips", settings.muteVideos, onSetMute)

            ToggleCard("Show clock", "Time and date in the corner", settings.showClock, onSetShowClock)

            ToggleCard("Show photo date", "When each photo was taken", settings.showPhotoDate, onSetShowPhotoDate)

            ToggleCard(
                "Sleep when alone (experimental)",
                "Best for a passby/hallway frame. Wakes when you walk up, sleeps ~2 min after you leave. " +
                    "It can't tell you're there if you sit still, so it may go dark while you linger. Camera shutter must be open.",
                settings.sleepWhenAlone,
                onSetSleepWhenAlone,
            )

            WeatherCard(
                settings = settings,
                geoStatus = geoStatus,
                geoResults = geoResults,
                onSetShowWeather = onSetShowWeather,
                onSearchLocation = onSearchLocation,
                onChooseLocation = onChooseLocation,
                onDetectLocation = onDetectLocation,
            )
        }
    }
}

/** A setting card that is just a label/subtitle and a switch; the whole card toggles. */
@Composable
private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingCard(onClick = { onChange(!checked) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 24.sp)
                Text(subtitle, color = SubtitleColor, fontSize = 16.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** Weather toggle plus, when on, the configured place, a city search, and the match list. */
@Composable
private fun WeatherCard(
    settings: SettingsState,
    geoStatus: String?,
    geoResults: List<GeoPlace>,
    onSetShowWeather: (Boolean) -> Unit,
    onSearchLocation: (String) -> Unit,
    onChooseLocation: (GeoPlace) -> Unit,
    onDetectLocation: () -> Unit,
) {
    SettingCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Weather", color = Color.White, fontSize = 24.sp)
                Text("Show current conditions", color = SubtitleColor, fontSize = 16.sp)
            }
            Switch(checked = settings.showWeather, onCheckedChange = onSetShowWeather)
        }
        if (settings.showWeather) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Location: ${settings.weatherPlace ?: "not set"}",
                color = Color.White,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDetectLocation,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Use my location", fontSize = 20.sp)
            }
            Spacer(Modifier.height(12.dp))
            var city by remember { mutableStateOf("") }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    singleLine = true,
                    placeholder = { Text("Search for a city", color = SubtitleColor) },
                    textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = SubtitleColor,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSearchLocation(city) },
                    enabled = city.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                ) {
                    Text("Search", fontSize = 20.sp)
                }
            }
            if (geoStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(geoStatus, color = SubtitleColor, fontSize = 16.sp)
            }
            // Multiple matches (e.g. Redmond, WA vs Redmond, OR) — tap to choose one.
            if (geoResults.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                geoResults.forEach { place ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ChipUnselected)
                            .clickable { onChooseLocation(place) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(place.label, color = Color.White, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

/** A rounded surface that groups one setting. Optionally tappable as a whole. */
@Composable
private fun SettingCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(CardColor)
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(24.dp),
        content = content,
    )
}

/** Equal-width filled chips; the selected one uses the accent, the rest a dark fill. */
@Composable
private fun <T> ChipRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { (label, value) ->
            val isSel = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary else ChipUnselected)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else Color.White,
                    fontSize = 20.sp,
                )
            }
        }
    }
}

/** Full-screen grid of downloaded media: tap to select, then remove. */
@Composable
private fun ManageScreen(
    items: List<CachedItem>,
    onRemove: (Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val totalMb = remember(items) { items.sumOf { it.file.length() } / (1024 * 1024) }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = 64.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Manage media", color = Color.White, fontSize = 32.sp)
                Text("${items.size} items · $totalMb MB", color = Color.LightGray, fontSize = 18.sp)
            }
            TextButton(onClick = onClose, modifier = Modifier.height(56.dp)) {
                Text("Done", fontSize = 20.sp, color = Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val isSel = item.id in selected
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .background(Color.DarkGray)
                        .clickable {
                            selected = if (isSel) selected - item.id else selected + item.id
                        }
                        .then(if (isSel) Modifier.border(4.dp, Color.White) else Modifier),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(item.file).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isVideo) {
                        Text(
                            "▶",
                            color = Color.White,
                            fontSize = 28.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (isSel) {
                        Text(
                            "✓",
                            color = Color.White,
                            fontSize = 28.sp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(Color(0xCC0066FF))
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onRemove(selected); selected = emptySet() },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.height(72.dp),
            ) {
                Text(if (selected.isEmpty()) "Remove" else "Remove (${selected.size})", fontSize = 22.sp)
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = { selected = emptySet() }, modifier = Modifier.height(56.dp)) {
                    Text("Clear", fontSize = 20.sp, color = Color.LightGray)
                }
            }
        }
    }
}

@Composable
private fun BlurredFillPhoto(file: File, effect: SlideEffect) {
    Box(Modifier.fillMaxSize()) {
        // Fill the aspect-ratio gap with a blurred zoom of the same photo (like Google's
        // ambient mode). Cheap blur: decode the photo tiny and let it upscale to fill —
        // Compose's Modifier.blur needs API 31, but Portal is API 29.
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(file).size(96).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Mute the background so the sharp foreground stays the focus.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        // The sharp, fully-visible photo on top — animated (Ken Burns) or static per setting.
        if (effect == SlideEffect.KEN_BURNS) KenBurnsImage(file) else StaticImage(file)
    }
}

@Composable
private fun StaticImage(file: File) {
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun KenBurnsImage(file: File) {
    // Fit the whole photo within the screen (letterbox/pillarbox on black for aspect
    // ratios that don't match the Portal's ~1280x800). A gentle zoom keeps some life
    // without cropping much of what we just fit.
    val scale = remember(file) { Animatable(1f) }
    LaunchedEffect(file) {
        scale.animateTo(KEN_BURNS_MAX_SCALE, animationSpec = tween(KEN_BURNS_MS, easing = LinearEasing))
    }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    )
}

@Suppress("DEPRECATION")
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(file: File, playing: Boolean, muted: Boolean, onEnded: () -> Unit) {
    var isMuted by remember(muted) { mutableStateOf(muted) }
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }
    }
    // Pause playback when an overlay covers the slideshow (so audio stops under a submenu).
    LaunchedEffect(playing) { player.playWhenReady = playing }
    LaunchedEffect(isMuted) { player.volume = if (isMuted) 0f else 1f }
    DisposableEffect(file) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = { isMuted = !isMuted },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 32.dp, end = 40.dp)
                .size(72.dp)
                .background(Color.Black.copy(alpha = 0.4f), shape = CircleShape)
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute video" else "Mute video",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
