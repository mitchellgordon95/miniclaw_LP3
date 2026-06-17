package ai.mytextpal.miniclaw

import android.content.Context
import android.content.Intent

/**
 * Tiny shared bridge between the trigger sources (the AccessibilityService that hears the DJI
 * button screen-on, and the MediaSession WakeService that hears the earbud button while
 * locked/screen-off) and the Activity (which runs the record/transcribe/reply pipeline).
 *
 * Two actions map to the two earbud gestures:
 *  - [fire] = single tap ("advance the loop": start → confirm/send → stop-and-relisten).
 *  - [cancel] = double tap ("back out": discard a recording, or stop the AI without re-listening).
 */
object Summon {
    /** True while MainActivity is in the foreground. */
    @Volatile var activityResumed = false

    /** The Activity's "primary"/single-tap and "abort"/double-tap handlers, while it's resumed. */
    @Volatile var activityPrimary: (() -> Unit)? = null
    @Volatile var activityAbort: (() -> Unit)? = null

    private var lastFire = 0L

    /** Debounce — the HID/AVRCP button can emit repeats; ignore presses within 350ms. */
    private fun debounced(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastFire < 350L) return
        lastFire = now
        action()
    }

    /**
     * Single-tap / primary. If the Activity is up, advance its state machine in place; otherwise
     * launch it (waking the screen / showing over the lockscreen) with the trigger extra so it
     * starts recording as soon as it resumes.
     */
    fun fire(context: Context) {
        debounced {
            val primary = activityPrimary
            if (activityResumed && primary != null) {
                primary()
            } else {
                val i = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_TRIGGER, true)
                context.startActivity(i)
            }
        }
    }

    /**
     * Double-tap / abort. Only meaningful when the Activity is up and doing something (recording
     * or responding); if nothing's happening there's nothing to back out of, so ignore it.
     */
    fun cancel(context: Context) {
        debounced {
            if (activityResumed) activityAbort?.invoke()
        }
    }
}
