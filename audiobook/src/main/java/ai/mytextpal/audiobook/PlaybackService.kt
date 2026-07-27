package ai.mytextpal.audiobook

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class PlaybackService : MediaSessionService() {

    companion object {
        const val CMD_PLAY_BOOK = "ai.mytextpal.audiobook.PLAY_BOOK"
        const val CMD_SLEEP = "ai.mytextpal.audiobook.SLEEP"
        const val ARG_BOOK_ID = "bookId"
        const val ARG_MILLIS = "millis"

        // UI reads these directly (same process) instead of round-tripping custom commands.
        val currentBookId = MutableStateFlow<String?>(null)
        val sleepEndsAt = MutableStateFlow(0L) // epoch ms; 0 = no timer

        // Release the session after this long paused, so the earbuds' media button falls
        // back to the MiniClaw wake session instead of resuming a book you finished with.
        private const val IDLE_STOP_MS = 10 * 60_000L
        private const val SAVE_INTERVAL_MS = 5_000L
    }

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var store: PositionStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleStopJob: Job? = null
    private var sleepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = PositionStore(this)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when the buds disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    idleStopJob?.cancel()
                } else {
                    saveNow()
                    scheduleIdleStop()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveNow()
            }

            override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                saveNow()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val id = currentBookId.value ?: return
                    store.save(id, PositionStore.Saved(0, 0L, player.playbackParameters.speed, System.currentTimeMillis(), finished = true))
                }
            }
        })

        val openUi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openUi)
            .setCallback(SessionCallback())
            .build()

        // Periodic save while playing, so a crash/battery-death loses at most ~5s of position.
        scope.launch {
            while (isActive) {
                if (player.isPlaying) saveNow()
                delay(SAVE_INTERVAL_MS)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        saveNow()
        sleepEndsAt.value = 0L
        currentBookId.value = null
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PLAY_BOOK, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_PLAY_BOOK -> args.getString(ARG_BOOK_ID)?.let { playBook(it) }
                CMD_SLEEP -> setSleepTimer(args.getLong(ARG_MILLIS, 0L))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun playBook(bookId: String) {
        // Switching books: persist the old one before the player state is torn down.
        if (currentBookId.value != null && currentBookId.value != bookId) saveNow()

        val book = Library.scan().find { it.id == bookId } ?: return
        currentBookId.value = book.id

        val items = book.files.mapIndexed { i, f ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(f))
                .setMediaId(f.absolutePath)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .setArtist(
                            if (book.files.size > 1) "Part ${i + 1} of ${book.files.size}"
                            else f.nameWithoutExtension,
                        )
                        .build(),
                )
                .build()
        }

        val saved = store.load(book.id)
        val (index, pos, speed) = if (saved == null || saved.finished) {
            Triple(0, 0L, saved?.speed ?: 1.0f)
        } else {
            // Rewind a little on resume so you get context back; more the longer you've been away.
            val gap = System.currentTimeMillis() - saved.updatedAt
            val rewind = when {
                gap < 60_000L -> 0L
                gap < 3_600_000L -> 10_000L
                gap < 86_400_000L -> 20_000L
                else -> 30_000L
            }
            Triple(
                saved.fileIndex.coerceIn(0, items.size - 1),
                max(0L, saved.posMs - rewind),
                saved.speed,
            )
        }

        player.setMediaItems(items, index, pos)
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.play()
    }

    private fun setSleepTimer(millis: Long) {
        sleepJob?.cancel()
        if (millis <= 0L) {
            sleepEndsAt.value = 0L
            return
        }
        sleepEndsAt.value = System.currentTimeMillis() + millis
        sleepJob = scope.launch {
            delay(millis)
            player.pause()
            sleepEndsAt.value = 0L
        }
    }

    private fun saveNow() {
        val id = currentBookId.value ?: return
        if (player.mediaItemCount == 0) return
        store.save(
            id,
            PositionStore.Saved(
                fileIndex = player.currentMediaItemIndex,
                posMs = max(0L, player.currentPosition),
                speed = player.playbackParameters.speed,
                updatedAt = System.currentTimeMillis(),
                finished = false,
            ),
        )
    }

    private fun scheduleIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            delay(IDLE_STOP_MS)
            if (!player.isPlaying) stopSelf()
        }
    }
}
