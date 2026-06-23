package com.ramnat.portalgphotos

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

enum class PowerPolicy {
    AWAKE_FOREVER,
    SLEEP_WHEN_ALONE
}

/**
 * Keeps our [PhotoDreamService] registered as the system screensaver.
 *
 * Portal's launcher unconditionally rewrites `screensaver_components` back to its own
 * dream on every boot (it holds WRITE_DREAM_STATE and re-asserts via WorkManager). We
 * can't stop that without root, so instead we re-apply our component after it: a
 * [BootReceiver] re-asserts immediately at boot (best-effort — may lose the boot race),
 * and a 15-minute periodic [ScreensaverWorker] guarantees convergence and re-seeds
 * itself across reboots. Writing the secure setting needs WRITE_SECURE_SETTINGS, granted
 * once over adb (it's a development permission; the grant persists across reboot):
 *
 *   adb shell pm grant com.ramnat.portalgphotos android.permission.WRITE_SECURE_SETTINGS
 */
object ScreensaverGuard {
    private const val TAG = "ScreensaverGuard"

    /** Fully-qualified component the Portal stores in screensaver_components. */
    const val COMPONENT = "com.ramnat.portalgphotos/com.ramnat.portalgphotos.PhotoDreamService"

    // Settings.Secure.SCREENSAVER_COMPONENTS is @hide, so use the literal key.
    private const val KEY = "screensaver_components"
    private const val KEY_ENABLED = "screensaver_enabled"
    private const val WORK_NAME = "screensaver_guard"

    /** Screen-off timeout (ms) used in "sleep when alone" mode. Long enough that presence
     *  motion-pulses keep resetting it while someone's around (so it doesn't blink), but
     *  short enough that an empty room sleeps reasonably soon. See the presence research. */
    private const val SLEEP_ALONE_TIMEOUT_MS = 900_000 // 15 minutes
    private const val GUARD_PREFS = "screensaver_guard"
    private const val KEY_SAVED_TIMEOUT = "saved_screen_off_timeout"
    private const val KEY_SAVED_SLEEP_TIMEOUT = "saved_sleep_timeout"
    private const val KEY_SAVED_SLEEP_TIMEOUT_CHARGING = "saved_sleep_timeout_charging"
    private const val KEY_SAVED_SLEEP_TIMEOUT_DISCHARGING = "saved_sleep_timeout_discharging"

    // Custom Aloha OS keys
    private const val KEY_SLEEP_TIMEOUT = "sleep_timeout"
    private const val KEY_SLEEP_TIMEOUT_CHARGING = "sleep_timeout_charging"
    private const val KEY_SLEEP_TIMEOUT_DISCHARGING = "sleep_timeout_discharging"

    /** True while *we* hold the sleep-when-alone timeout override. Tracks ownership
     *  explicitly because SLEEP_ALONE_TIMEOUT_MS (15 min) is also a legitimate user choice,
     *  so we can't tell "our override" from "the user's value" by comparing the number. */
    private const val KEY_OVERRIDE_ACTIVE = "timeout_override_active"

    /**
     * Apply the screen power policy for the current mode.
     *
     * - **Always-on** (default): re-assert our [PhotoDreamService] as the screensaver so an
     *   idle Portal drops into our frame, and restore the normal screen-off timeout.
     * - **Sleep when alone**: disable the screensaver (otherwise the Dream fires every
     *   screen-off and flashes instead of dozing) and lengthen the screen-off timeout, so
     *   Portal's presence policy keeps the screen lit while there's movement and lets it
     *   doze off when the room is empty. [MainActivity] separately drops FLAG_KEEP_SCREEN_ON.
     *
     * Needs WRITE_SECURE_SETTINGS (screensaver) and WRITE_SETTINGS (screen-off timeout),
     * both granted once over adb. Silent no-op if a grant is missing.
     */
    fun applyPowerPolicy(context: Context, policy: PowerPolicy) {
        val cr = context.contentResolver
        val prefs = context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
        try {
            val overrideActive = prefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)

            when (policy) {
                PowerPolicy.SLEEP_WHEN_ALONE -> {
                    Settings.Secure.putInt(cr, KEY_ENABLED, 0)
                    // Capture the user's real timeout only on the transition INTO the override.
                    // If we already own it, the current system value is our own sentinel — don't
                    // re-capture it (that would poison the restore value to 15 min).
                    if (!overrideActive) {
                        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 15_000)
                        val curSleep = Settings.Secure.getInt(cr, KEY_SLEEP_TIMEOUT, -1)
                        val curSleepCharging = Settings.Secure.getInt(cr, KEY_SLEEP_TIMEOUT_CHARGING, -1)
                        val curSleepDischarging = Settings.Secure.getInt(cr, KEY_SLEEP_TIMEOUT_DISCHARGING, -1)

                        prefs.edit()
                            .putInt(KEY_SAVED_TIMEOUT, cur)
                            .putInt(KEY_SAVED_SLEEP_TIMEOUT, curSleep)
                            .putInt(KEY_SAVED_SLEEP_TIMEOUT_CHARGING, curSleepCharging)
                            .putInt(KEY_SAVED_SLEEP_TIMEOUT_DISCHARGING, curSleepDischarging)
                            .putBoolean(KEY_OVERRIDE_ACTIVE, true)
                            .apply()
                        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, SLEEP_ALONE_TIMEOUT_MS)
                        Settings.Secure.putInt(cr, KEY_SLEEP_TIMEOUT, -1)
                        Settings.Secure.putInt(cr, KEY_SLEEP_TIMEOUT_CHARGING, -1)
                        Settings.Secure.putInt(cr, KEY_SLEEP_TIMEOUT_DISCHARGING, -1)
                        debugLog(TAG) { "sleep-when-alone: screensaver off, timeout ${SLEEP_ALONE_TIMEOUT_MS}ms (saved ${cur}ms)" }
                    } else {
                        debugLog(TAG) { "sleep-when-alone: already active, leaving timeout as-is" }
                    }
                }
                PowerPolicy.AWAKE_FOREVER -> {
                    Settings.Secure.putInt(cr, KEY_ENABLED, 1)
                    // Only restore if WE installed the override. Otherwise leave whatever the
                    // user has chosen — don't clobber a manually-picked timeout.
                    if (overrideActive) {
                        val saved = prefs.getInt(KEY_SAVED_TIMEOUT, 15_000)
                        val savedSleep = prefs.getInt(KEY_SAVED_SLEEP_TIMEOUT, -1)
                        val savedSleepCharging = prefs.getInt(KEY_SAVED_SLEEP_TIMEOUT_CHARGING, -1)
                        val savedSleepDischarging = prefs.getInt(KEY_SAVED_SLEEP_TIMEOUT_DISCHARGING, -1)

                        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, saved)
                        Settings.Secure.putInt(cr, KEY_SLEEP_TIMEOUT, savedSleep)
                        Settings.Secure.putInt(cr, KEY_SLEEP_TIMEOUT_CHARGING, savedSleepCharging)
                        Settings.Secure.putInt(cr, KEY_SLEEP_TIMEOUT_DISCHARGING, savedSleepDischarging)
                        
                        prefs.edit().putBoolean(KEY_OVERRIDE_ACTIVE, false).apply()
                        debugLog(TAG) { "awake-forever: screensaver on, timeout restored to ${saved}ms" }
                    }
                    applyNow(context) // re-assert our screensaver component
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "power policy needs WRITE_SECURE_SETTINGS + WRITE_SETTINGS (grant via adb)", e)
        }
    }

    /** Re-assert our component if it has drifted. Idempotent and silent when already set. */
    fun applyNow(context: Context): Boolean {
        return try {
            val current = Settings.Secure.getString(context.contentResolver, KEY)
            if (current != COMPONENT) {
                Settings.Secure.putString(context.contentResolver, KEY, COMPONENT)
                debugLog(TAG) { "re-asserted screensaver_components (was: $current)" }
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted — run: adb shell pm grant " +
                "com.ramnat.portalgphotos android.permission.WRITE_SECURE_SETTINGS", e)
            false
        }
    }

    /** Enqueue the periodic guard (KEEP — survives reboot, won't duplicate). */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ScreensaverWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Re-assert a few times over the first few minutes after boot. The immediate write
     * loses the race with the launcher's boot-time overwrite, so these delayed passes are
     * what actually land *after* it. Staggered because the launcher's write time isn't
     * known precisely.
     */
    fun scheduleBootReassert(context: Context) {
        val wm = WorkManager.getInstance(context)
        listOf(15L, 60L, 180L).forEachIndexed { i, delaySec ->
            val request = OneTimeWorkRequestBuilder<ScreensaverWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniqueWork("${WORK_NAME}_boot_$i", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class ScreensaverWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {
    override fun doWork(): Result {
        // Re-assert the screensaver component to win the boot race against Portal's launcher.
        // We do not re-apply the full dynamic power policy here because the Activity manages
        // the active screen timeout state.
        ScreensaverGuard.applyNow(applicationContext)
        return Result.success()
    }
}
