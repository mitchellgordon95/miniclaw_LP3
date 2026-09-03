package ai.mytextpal.books

import android.util.Log
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerException
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one player for the tool, living for the whole process rather than one screen.
 *
 * Detached playback means the SDK's media service keeps playing after the screen that
 * started it is gone — but somebody still has to keep saving the position while the book
 * plays in the background. Screens come and go; this object doesn't.
 *
 * Handle lifetime: while the handle is open the SDK service never idles out. So ten
 * minutes after a pause we let go of it (the service then stops itself after its own
 * 15-minute idle rule), which also hands the earbuds' media button back to whatever
 * played before. Opening a book, or pressing play, reconnects — to the still-live queue
 * if the service is around, or to a fresh one loaded from the saved position if not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Playback {
    private const val TAG = "BooksPlayback"
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val IDLE_RELEASE_MS = 10 * 60_000L
    private const val PREPARE_TIMEOUT_MS = 8_000L
    const val SKIP_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var audio: LightAudio? = null
    private var store: PositionStore? = null
    private var started = false

    private val playerFlow = MutableStateFlow<LightAudioPlayer?>(null)

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    val isPlaying: StateFlow<Boolean> = playerFlow
        .flatMapLatest { it?.isPlaying ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val positionMs: StateFlow<Long> = playerFlow
        .flatMapLatest { it?.positionMs ?: flowOf(0L) }
        .stateIn(scope, SharingStarted.Eagerly, 0L)
    val durationMs: StateFlow<Long> = playerFlow
        .flatMapLatest { it?.durationMs ?: flowOf(0L) }
        .stateIn(scope, SharingStarted.Eagerly, 0L)
    val partIndex: StateFlow<Int> = playerFlow
        .flatMapLatest { it?.currentMediaItemIndex ?: flowOf(-1) }
        .stateIn(scope, SharingStarted.Eagerly, -1)
    val error: StateFlow<LightAudioError?> = playerFlow
        .flatMapLatest { it?.error ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Epoch ms when the sleep timer pauses playback; 0 = no timer. */
    private val _sleepEndsAt = MutableStateFlow(0L)
    val sleepEndsAt: StateFlow<Long> = _sleepEndsAt.asStateFlow()

    /** Set when a player command fails outright (no capability, service refused). */
    private val _fault = MutableStateFlow<String?>(null)
    val fault: StateFlow<String?> = _fault.asStateFlow()

    /** True while a book's queue is being prepared and seeked; the UI shows "Opening…". */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var pausedAt = 0L
    private var idleJob: Job? = null
    private var sleepJob: Job? = null
    private val isLoading: Boolean get() = _loading.value

    /** Called by the first screen. Later calls just refresh the audio factory (new activity). */
    fun start(audio: LightAudio, store: PositionStore) {
        this.audio = audio
        this.store = store
        if (started) return
        started = true

        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                if (isPlaying.value) saveNow()
            }
        }
        scope.launch {
            isPlaying.drop(1).collect { playing ->
                if (playing) {
                    idleJob?.cancel()
                } else {
                    pausedAt = System.currentTimeMillis()
                    saveNow()
                    scheduleIdleRelease()
                }
            }
        }
        scope.launch {
            partIndex.drop(1).collect { if (it >= 0) saveNow() }
        }
    }

    /** Start (or continue) [book] from where the listener left off. */
    fun open(book: Book) {
        scope.launch {
            if (_book.value?.id == book.id && playerFlow.value != null && !isLoading) {
                if (!isPlaying.value) resume()
                return@launch
            }
            saveNow()
            loadAndPlay(book)
        }
    }

    fun togglePlayPause() {
        val player = playerFlow.value
        if (player != null && isPlaying.value) player.pause() else scope.launch { resume() }
    }

    fun seekTo(ms: Long) {
        playerFlow.value?.seekTo(ms.coerceIn(0L, durationMs.value.coerceAtLeast(0L)))
    }

    fun skip(deltaMs: Long) = seekTo(positionMs.value + deltaMs)

    fun nextPart() {
        playerFlow.value?.skipToNext()
    }

    fun previousPart() {
        playerFlow.value?.skipToPrevious()
    }

    fun cycleSpeed() = setSpeed(nextSpeed(_speed.value))

    fun setSpeed(value: Float) {
        _speed.value = value
        playerFlow.value?.speed = value
        scope.launch { saveNow() }
    }

    fun cycleSleep() {
        val remaining = (_sleepEndsAt.value - System.currentTimeMillis()).coerceAtLeast(0L)
        setSleep(nextSleepMs(remaining))
    }

    fun setSleep(ms: Long) {
        sleepJob?.cancel()
        if (ms <= 0L) {
            _sleepEndsAt.value = 0L
            return
        }
        _sleepEndsAt.value = System.currentTimeMillis() + ms
        sleepJob = scope.launch {
            delay(ms)
            playerFlow.value?.pause()
            _sleepEndsAt.value = 0L
        }
    }

    /** Forget the saved spot and start [book] from the top. */
    fun restart(book: Book) {
        scope.launch {
            store?.clear(book.id)
            if (_book.value?.id == book.id) {
                _book.value = null
                loadAndPlay(book)
            }
        }
    }

    // ---- internals ----

    private suspend fun resume() {
        val book = _book.value ?: return
        val player = ensurePlayer() ?: return
        if (player.currentMediaItemIndex.value < 0) {
            // The service idled out while we were away; rebuild from the saved spot.
            loadAndPlay(book)
            return
        }
        val rewind = resumeRewindMs(System.currentTimeMillis() - pausedAt)
        if (rewind > 0L && positionMs.value > 0L) player.seekTo((positionMs.value - rewind).coerceAtLeast(0L))
        player.play()
    }

    private suspend fun loadAndPlay(book: Book) {
        val store = store ?: return
        _loading.value = true
        val player = ensurePlayer() ?: run { _loading.value = false; return }
        try {
            _book.value = book
            val start = startPoint(store.load(book.id), book.files.size, System.currentTimeMillis())
            _speed.value = start.speed
            player.speed = start.speed
            player.setMediaQueue(book.toQueue(), start.fileIndex)
            if (start.posMs > 0L) {
                // seekTo clamps to the resolved duration, so wait until the part is prepared.
                val t0 = System.currentTimeMillis()
                val known = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { player.durationMs.first { it > 0L } }
                Log.i(TAG, "Prepared wait: ${System.currentTimeMillis() - t0} ms, duration=$known, index=${player.currentMediaItemIndex.value}")
                player.seekTo(start.posMs)
            }
            player.play()
        } finally {
            _loading.value = false
        }
    }

    private suspend fun ensurePlayer(): LightAudioPlayer? {
        playerFlow.value?.let { return it }
        val audio = audio ?: return null
        val player = try {
            audio.newPlayer(usage = LightAudioUsage.Speech, playback = LightAudioPlayback.Detached)
        } catch (e: LightAudioPlayerException) {
            Log.e(TAG, "Cannot create detached player", e)
            _fault.value = e.message ?: "Cannot start audio"
            return null
        }
        if (!player.awaitReady()) {
            Log.e(TAG, "Player released before it became ready")
            _fault.value = "Audio service unavailable"
            return null
        }
        _fault.value = null
        playerFlow.value = player
        return player
    }

    private suspend fun saveNow() {
        val store = store ?: return
        val book = _book.value ?: return
        val index = partIndex.value
        // While a queue is being swapped in, the flows briefly describe the wrong book.
        if (index < 0 || isLoading) return
        val finished = isBookFinished(index, book.files.size, positionMs.value, durationMs.value) && !isPlaying.value
        store.save(
            book.id,
            SavedPosition(
                fileIndex = if (finished) 0 else index,
                posMs = if (finished) 0L else positionMs.value.coerceAtLeast(0L),
                speed = _speed.value,
                updatedAt = System.currentTimeMillis(),
                finished = finished,
            ),
        )
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (isPlaying.value) return@launch
            saveNow()
            playerFlow.value?.release()
            playerFlow.value = null
            setSleep(0L)
        }
    }

    private fun Book.toQueue(): List<LightAudioItem> = files.mapIndexed { i, file ->
        LightAudioItem(
            source = LightAudioSource.FileSource(file),
            metadata = LightMediaMetadata(
                title = title,
                artist = if (files.size > 1) "Part ${i + 1} of ${files.size}" else file.nameWithoutExtension,
                album = title,
            ),
        )
    }
}
