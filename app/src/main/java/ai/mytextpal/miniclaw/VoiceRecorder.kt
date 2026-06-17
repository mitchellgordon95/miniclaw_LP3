package ai.mytextpal.miniclaw

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Records mic audio to an AAC/MP4 file in the cache dir (accepted by /api/transcribe).
 *
 * Input is always the DJI receiver on USB. We deliberately do NOT use the Bluetooth earbuds'
 * mic: capturing it requires a Bluetooth SCO ("call mode") link, which flips the earbuds out of
 * media mode and hands their button to the telecom call handler — so the button can no longer
 * start/stop a recording. With the USB mic the earbuds stay in A2DP media mode and their button
 * keeps working as our control. [start] returns false if the USB mic isn't connected.
 */
class VoiceRecorder(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** True when the DJI (USB) mic is plugged in and available as a capture device. */
    fun isUsbMicConnected(): Boolean = findUsbMic() != null

    private fun findUsbMic(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    fun start(): Boolean {
        val usb = findUsbMic() ?: run {
            Log.w(TAG, "no USB (DJI) mic connected")
            return false
        }
        return try {
            val file = File(context.cacheDir, "utterance.mp4")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.setPreferredDevice(usb)
            rec.start()
            recorder = rec
            outputFile = file
            true
        } catch (e: Exception) {
            Log.w(TAG, "record start failed", e)
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            false
        }
    }

    /** Stops recording and returns the recorded file, or null on failure. */
    fun stop(): File? {
        val rec = recorder ?: return null
        return try {
            rec.stop()
            rec.release()
            recorder = null
            outputFile
        } catch (e: Exception) {
            try { rec.release() } catch (_: Exception) {}
            recorder = null
            null
        }
    }

    /** Stops and discards the current recording (used for cancel). */
    fun cancel() {
        val rec = recorder ?: return
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
        recorder = null
    }

    companion object {
        private const val TAG = "VoiceRecorder"
    }
}
