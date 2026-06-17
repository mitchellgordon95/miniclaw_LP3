package ai.mytextpal.miniclaw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the earbud-button [WakeService] after a reboot so the trigger survives without
 *  first opening the app. BOOT_COMPLETED is an allowed entry point for starting a mediaPlayback
 *  foreground service from the background. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        }
    }
}
