package com.ramnat.portalgphotos

import android.util.Log

/**
 * Emits a log message at INFO level (to bypass production filters)
 * ONLY if the provided tag has DEBUG logging explicitly enabled via system properties.
 *
 * Enable via ADB:
 * adb shell setprop log.tag.MyTag DEBUG
 */
inline fun debugLog(tag: String, message: () -> String) {
    if (Log.isLoggable(tag, Log.DEBUG)) {
        Log.i(tag, message())
    }
}
