package com.ramnat.portalgphotos

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramnat.portalgphotos.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply the screen power policy for the current mode (always-on vs sleep-when-alone)
        // and seed the periodic guard that keeps our screensaver component asserted.
        ScreensaverGuard.applyPowerPolicy(this, com.ramnat.portalgphotos.data.AppSettings(this).load().sleepWhenAlone)
        ScreensaverGuard.ensureScheduled(this)
        enterImmersive()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val settings by vm.settingsState.collectAsStateWithLifecycle()
            // Hold the screen awake for a permanent frame — unless "sleep when alone" is on,
            // in which case we release it so Portal's presence policy governs sleep-vs-show.
            LaunchedEffect(settings.sleepWhenAlone) {
                if (settings.sleepWhenAlone)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val weather by vm.weatherState.collectAsStateWithLifecycle()
            val geoStatus by vm.geoStatus.collectAsStateWithLifecycle()
            val geoResults by vm.geoResults.collectAsStateWithLifecycle()

            // Location permission launcher: on grant, trigger GPS detection.
            val locationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                if (grants.values.any { it }) vm.detectLocation()
            }

            AppRoot(
                state = state,
                settings = settings,
                weather = weather,
                geoStatus = geoStatus,
                geoResults = geoResults,
                onAdd = vm::addPhotos,
                onReplace = vm::replacePhotos,
                onCancel = vm::cancelPicker,
                onRetry = vm::start,
                onSignIn = vm::signIn,
                onSetShuffle = vm::setShuffle,
                onSetInterval = vm::setIntervalMs,
                onSetEffect = vm::setEffect,
                onSetMute = vm::setMuteVideos,
                onSetShowClock = vm::setShowClock,
                onSetShowPhotoDate = vm::setShowPhotoDate,
                onSetShowWeather = vm::setShowWeather,
                onSetSleepWhenAlone = vm::setSleepWhenAlone,
                onSearchLocation = vm::searchLocation,
                onChooseLocation = vm::chooseLocation,
                onDetectLocation = {
                    if (vm.hasLocationPermission()) {
                        vm.detectLocation()
                    } else {
                        locationLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ))
                    }
                },
                onRemoveItems = vm::removeItems,
            )
        }
    }

    // Immersive flags get cleared whenever the window loses focus (returning from the
    // browser via singleTask, the Dream launching us, transient bars). Re-apply on every
    // focus gain, not just onCreate, or Portal's top bar reappears as a black strip.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // Draw edge-to-edge and hide the system bars (immersive), like the native Photo Booth
    // app — the photo uses the whole screen, system pills float on top.
    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
