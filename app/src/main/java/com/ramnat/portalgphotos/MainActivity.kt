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
        ScreensaverGuard.ensureScheduled(this)
        enterImmersive()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val settings by vm.settingsState.collectAsStateWithLifecycle()
            val powerPolicy by vm.powerPolicy.collectAsStateWithLifecycle()
            LaunchedEffect(powerPolicy) {
                // Determine if we should hold the screen awake. We only hold it if we want AWAKE_FOREVER.
                // Otherwise, we clear the flag and let the OS handle the screen timeout based on the policy.
                if (powerPolicy == PowerPolicy.AWAKE_FOREVER) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                ScreensaverGuard.applyPowerPolicy(
                    this@MainActivity,
                    powerPolicy
                )
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
                onSetBackgroundStyle = vm::setBackgroundStyle,
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

    override fun onResume() {
        super.onResume()
        // Re-apply the user's preferred policy when the app is in the foreground.
        // This ensures SLEEP_WHEN_ALONE disables the screensaver so we can doze gracefully.
        val sleepAlone = com.ramnat.portalgphotos.data.AppSettings(this).load().sleepWhenAlone
        val policy = if (sleepAlone) PowerPolicy.SLEEP_WHEN_ALONE else PowerPolicy.AWAKE_FOREVER
        ScreensaverGuard.applyPowerPolicy(this, policy)
    }

    override fun onStop() {
        super.onStop()
        // Re-enable the screensaver when the app goes to the background.
        // This ensures the Portal's launcher "Photos" button continues to work!
        ScreensaverGuard.applyPowerPolicy(this, PowerPolicy.AWAKE_FOREVER)
    }
}
