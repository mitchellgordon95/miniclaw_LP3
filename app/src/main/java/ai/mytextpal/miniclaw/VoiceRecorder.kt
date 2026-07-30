package ai.mytextpal.miniclaw

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Records mic audio to an AAC/MP4 file in the cache dir (accepted by /api/transcribe).
 *
 * Input is the Bluetooth earbuds' mic (Raycon Essential Open) when they're connected, else the
 * phone's own mic. The earbud mic needs an HFP/SCO ("call audio") link, which we bring up only
 * for the duration of a recording via [AudioManager.setCommunicationDevice] and tear down as
 * soon as it stops — so between recordings the buds sit in normal A2DP media mode and their tap
 * gestures keep routing to WakeService's MediaSession.
 *
 * Caveat while SCO is up: some earbud firmware treats a tap as "answer/end call" instead of
 * play/pause, so the tap may not reach us mid-recording. MainActivity compensates with a
 * silence-based auto-stop, so a recording always completes hands-free.
 */
class VoiceRecorder(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var scoRouted = false

    val isRecording: Boolean get() = recorder != null

    /** True while the current recording is capturing from the earbud (SCO) mic. */
    var usingBluetoothMic = false
        private set

    fun isBluetoothMicConnected(): Boolean = findBluetoothMic() != null

    private fun findBluetoothMic(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    /**
     * Starts recording, preferring the earbud mic. Suspends briefly (≲1.5s) while the SCO link
     * comes up; safe to call from the main thread.
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        val btMic = findBluetoothMic()
        if (btMic != null) routeToBluetooth(btMic)
        usingBluetoothMic = btMic != null && scoRouted
        try {
            val file = File(context.cacheDir, "utterance.mp4")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            if (usingBluetoothMic) {
                // SCO is an 8/16kHz speech link — ask for exactly what it can deliver.
                rec.setAudioSamplingRate(16_000)
                rec.setAudioEncodingBitRate(48_000)
            } else {
                rec.setAudioSamplingRate(44_100)
                rec.setAudioEncodingBitRate(96_000)
            }
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            if (usingBluetoothMic) rec.setPreferredDevice(btMic)
            rec.start()
            recorder = rec
            outputFile = file
            true
        } catch (e: Exception) {
            Log.w(TAG, "record start failed", e)
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            releaseRouting()
            false
        }
    }

    /**
     * setCommunicationDevice returns before the SCO link is actually live, and audio captured
     * before then is silence. There's no clean "SCO up" signal without the BluetoothHeadset
     * profile proxy, so poll the applied route, then give it a short settle.
     */
    private suspend fun routeToBluetooth(device: AudioDeviceInfo) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!audioManager.setCommunicationDevice(device)) {
                Log.w(TAG, "setCommunicationDevice refused")
                releaseRouting()
                return
            }
            scoRouted = true
            var waited = 0L
            while (waited < 1_000 && audioManager.communicationDevice?.id != device.id) {
                delay(50); waited += 50
            }
            delay(250)
            Log.d(TAG, "bluetooth mic routed after ${waited}ms")
        } catch (e: Exception) {
            Log.w(TAG, "bluetooth mic routing failed", e)
            releaseRouting()
        }
    }

    private fun releaseRouting() {
        if (scoRouted) {
            try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
            try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
            scoRouted = false
        }
        usingBluetoothMic = false
    }

    /**
     * Max input amplitude since the last call (0–32767); MediaRecorder resets it on read, which
     * is exactly the polling semantics the silence auto-stop wants. 0 when not recording.
     */
    fun maxAmplitude(): Int = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }

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
        } finally {
            releaseRouting()
        }
    }

    /** Stops and discards the current recording (used for cancel). */
    fun cancel() {
        val rec = recorder ?: return
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
        recorder = null
        releaseRouting()
    }

    companion object {
        private const val TAG = "VoiceRecorder"
    }
}
