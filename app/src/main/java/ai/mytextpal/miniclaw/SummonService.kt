package ai.mytextpal.miniclaw

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Summon MiniClaw with a double-press of one of the LP3's hardware buttons.
 *
 * An accessibility service with key filtering sees every hardware key before the foreground
 * app does, screen on or locked. Every event is passed through untouched (`return false`),
 * so the button keeps doing whatever LightOS does with it; we only *also* fire when the
 * trigger key is pressed twice within [DOUBLE_PRESS_MS].
 *
 * LP3 key codes (from Light's keyboard library):
 *   24 volume up · 25 volume down · 27 shutter (full press) · 80 shutter half-press ·
 *   317/318 scroll wheel turn · 319 scroll wheel click.
 *
 * The default trigger is the shutter *half-press*: outside the camera LightOS does nothing
 * with it, so a double half-press is a free gesture. Every hardware key press is logged at
 * debug level (tag "Summon") so you can pick another code from `adb logcat -s Summon`.
 *
 * Enable after installing (no UI for it on the LP3):
 *   adb shell settings put secure enabled_accessibility_services ai.mytextpal.miniclaw/ai.mytextpal.miniclaw.SummonService
 *   adb shell settings put secure accessibility_enabled 1
 */
class SummonService : AccessibilityService() {

    private var lastTriggerDownAt = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        Log.d(TAG, "hw key down code=${event.keyCode} device='${event.device?.name ?: "?"}'")
        if (event.keyCode != TRIGGER_KEYCODE) return false

        val now = event.eventTime
        if (now - lastTriggerDownAt in 1..DOUBLE_PRESS_MS) {
            lastTriggerDownAt = 0L
            Log.i(TAG, "double press of key $TRIGGER_KEYCODE → summon")
            Summon.fire(this)
        } else {
            lastTriggerDownAt = now
        }
        return false // never swallow the button; LightOS keeps its own behavior
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "Summon"

        /** Shutter half-press. Try 27 (shutter), 319 (wheel click), 24/25 (volume) instead. */
        const val TRIGGER_KEYCODE = 80

        /** Two presses closer together than this count as a double press. */
        const val DOUBLE_PRESS_MS = 450L
    }
}
