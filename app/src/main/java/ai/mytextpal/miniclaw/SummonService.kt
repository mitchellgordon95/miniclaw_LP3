package ai.mytextpal.miniclaw

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Global listener for the DJI mic button. The DJI receiver sends a HID VOLUME_UP whose input
 * device name contains "DJI"; we react only to that and consume it (so it doesn't change
 * volume), while letting the phone's own volume rocker pass through untouched.
 *
 * Works while the screen is on (foreground, backgrounded, other apps, lockscreen). Screen-off
 * idle is not reachable this way without root — the earbud media button ([[WakeService]]) covers
 * the locked/pocketed case instead.
 */
class SummonService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        val deviceName = event.device?.name ?: ""
        Log.d(TAG, "VOLUME_UP from device='$deviceName' action=${event.action}")
        val isDji = deviceName.contains("DJI", ignoreCase = true)
        if (!isDji) return false // phone volume rocker → let the system handle volume normally
        if (event.action == KeyEvent.ACTION_DOWN) Summon.fire(this)
        return true // consume the DJI press so volume doesn't change
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "Summon"
    }
}
