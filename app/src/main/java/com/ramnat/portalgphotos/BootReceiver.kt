package com.ramnat.portalgphotos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * On boot, immediately re-assert our screensaver (best-effort — the launcher resets it
 * around the same time) and make sure the periodic [ScreensaverWorker] is scheduled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScreensaverGuard.applyNow(context)          // immediate (likely loses the race)
            ScreensaverGuard.scheduleBootReassert(context) // delayed passes that land after
            ScreensaverGuard.ensureScheduled(context)   // long-term periodic backstop
        }
    }
}
