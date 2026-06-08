package com.ramnat.portalgphotos

import android.content.Intent
import android.service.dreams.DreamService

/**
 * Thin screensaver (Dream) that does NOT render anything itself — when Portal goes idle
 * and starts this Dream, it immediately launches [MainActivity] and dismisses itself.
 *
 * Why this shape: Android's only "device went idle" hook is the Dream. But hosting the
 * Compose slideshow *inside* the Dream was unreliable on Portal (touch traps, a
 * start/stop flashing loop — see git history / README). Using the Dream purely as a
 * launch trigger avoids all of that: MainActivity is a normal foreground app that holds
 * FLAG_KEEP_SCREEN_ON (verified to keep the display awake), so once it's up the idle
 * timer never fires again and the Dream never re-triggers. Exit is the normal Portal
 * back/home button.
 *
 * Wire it up as the active screensaver via adb (not baked into the APK):
 *   adb shell settings put secure screensaver_components com.ramnat.portalgphotos/.PhotoDreamService
 * Restore Portal default:
 *   adb shell settings put secure screensaver_components com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService
 */
class PhotoDreamService : DreamService() {

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        isInteractive = false
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish() // dismiss the dream; MainActivity takes over and keeps the screen on
    }
}
