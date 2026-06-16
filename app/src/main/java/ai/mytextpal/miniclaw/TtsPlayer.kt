package ai.mytextpal.miniclaw

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Speaks a reply sentence-by-sentence as it streams in.
 *
 * Each sentence is synthesized via the cloud TTS endpoint (better voice); if that returns null
 * it falls back to the on-device engine for that sentence. Synthesis runs in parallel with
 * playback, so the user hears sentence 1 while sentence 3 is still being generated.
 *
 * [onActiveChange] fires true when speech begins and false when it fully drains, so the UI can
 * flip the mic into a "stop" button. [isSpeaking] reflects the same state for tap handling.
 */
class TtsPlayer(
    private val context: Context,
    scope: CoroutineScope,
    private val synth: suspend (String) -> ByteArray?,
    private val androidTts: TextToSpeech,
    private val onActiveChange: (Boolean) -> Unit = {},
) {
    private data class Job(val text: String, val audio: Deferred<ByteArray?>, val gen: Int)

    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val workScope = scope
    private val pending = AtomicInteger(0)
    private var player: MediaPlayer? = null
    private var current: CancellableContinuation<Unit>? = null

    /** Bumped by stop(); queued/in-flight items from older generations are skipped. */
    @Volatile private var generation = 0

    val isSpeaking: Boolean get() = pending.get() > 0

    init {
        workScope.launch {
            for (job in queue) {
                try {
                    if (job.gen != generation) continue
                    val bytes = try { job.audio.await() } catch (e: Exception) { null }
                    if (job.gen != generation) continue
                    if (bytes != null) playBytes(bytes) else speakOnDevice(job.text)
                } finally {
                    if (pending.decrementAndGet() == 0) onActiveChange(false)
                }
            }
        }
    }

    /** Queue a sentence; synthesis starts immediately, in parallel with whatever is playing. */
    fun enqueue(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (pending.getAndIncrement() == 0) onActiveChange(true)
        val gen = generation
        val audio = workScope.async(Dispatchers.IO) {
            if (gen != generation) null else synth(t)
        }
        queue.trySend(Job(t, audio, gen))
    }

    /** Stop playback now and drop the queue. Remaining jobs are skipped (and drained to idle). */
    fun stop() {
        generation++
        current?.let { if (it.isActive) it.resume(Unit) }
        current = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { androidTts.stop() } catch (_: Exception) {}
    }

    private suspend fun playBytes(bytes: ByteArray) {
        val f = File(context.cacheDir, "tts-${System.nanoTime()}.mp3")
        try { f.writeBytes(bytes) } catch (e: Exception) { return }
        suspendCancellableCoroutine<Unit> { cont ->
            current = cont
            val mp = MediaPlayer()
            player = mp
            fun finish() {
                try { mp.release() } catch (_: Exception) {}
                f.delete()
                if (cont.isActive) cont.resume(Unit)
            }
            try {
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, _, _ -> finish(); true }
                mp.setDataSource(f.absolutePath)
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                finish()
            }
            cont.invokeOnCancellation { try { mp.release() } catch (_: Exception) {}; f.delete() }
        }
    }

    private suspend fun speakOnDevice(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        current = cont
        val id = "u-${System.nanoTime()}"
        androidTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
        })
        val r = androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
    }
}
