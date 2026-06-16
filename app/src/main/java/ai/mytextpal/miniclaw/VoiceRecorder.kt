package ai.mytextpal.miniclaw

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records mic audio to an AAC/MP4 file in the cache dir (accepted by /api/transcribe). */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
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
            rec.start()
            recorder = rec
            outputFile = file
            true
        } catch (e: Exception) {
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
}
