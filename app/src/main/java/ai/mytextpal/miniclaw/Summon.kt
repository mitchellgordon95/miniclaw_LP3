package ai.mytextpal.miniclaw

/**
 * Tiny shared bridge between the AccessibilityService (which hears the DJI button from
 * anywhere) and the Activity (which runs the record/transcribe/reply pipeline).
 */
object Summon {
    /** True while MainActivity is in the foreground. */
    @Volatile var activityResumed = false

    /** The Activity's record/stop toggle, registered while it's resumed. */
    @Volatile var activityTrigger: (() -> Unit)? = null

    private var lastFire = 0L

    /** Debounce — the HID button can emit repeats; ignore presses within 350ms. */
    fun debounced(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastFire < 350L) return
        lastFire = now
        action()
    }
}
