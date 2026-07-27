package ai.mytextpal.audiobook

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.PermissionChecker
import androidx.core.os.bundleOf
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    // Mirrors of player state, so Compose recomposes on player events.
    private var isPlaying by mutableStateOf(false)
    private var nowPlaying by mutableStateOf<MediaMetadata?>(null)
    private var itemCount by mutableIntStateOf(0)
    private var itemIndex by mutableIntStateOf(0)
    private var speed by mutableStateOf(1.0f)
    private var storageGranted by mutableStateOf(false)

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            isPlaying = player.isPlaying
            nowPlaying = player.mediaMetadata
            itemCount = player.mediaItemCount
            itemIndex = player.currentMediaItemIndex
            speed = player.playbackParameters.speed
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    onBackground = Color.White,
                    surface = Color.Black,
                    onSurface = Color.White,
                    primary = Color.White,
                    onPrimary = Color.Black,
                    secondary = Color.White,
                    outline = Color.White,
                ),
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    BooksApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        storageGranted = canReadBooks()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (future.isCancelled) return@addListener
            val c = future.get()
            c.addListener(playerListener)
            controller = c
            isPlaying = c.isPlaying
            nowPlaying = c.mediaMetadata
            itemCount = c.mediaItemCount
            itemIndex = c.currentMediaItemIndex
            speed = c.playbackParameters.speed
        }, MoreExecutors.directExecutor())
    }

    override fun onResume() {
        super.onResume()
        storageGranted = canReadBooks()
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun canReadBooks(): Boolean =
        Environment.isExternalStorageManager() ||
            PermissionChecker.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
            PermissionChecker.PERMISSION_GRANTED

    private fun playBook(bookId: String) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_PLAY_BOOK, Bundle.EMPTY),
            bundleOf(PlaybackService.ARG_BOOK_ID to bookId),
        )
    }

    private fun setSleep(millis: Long) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY),
            bundleOf(PlaybackService.ARG_MILLIS to millis),
        )
    }

    // ---------- UI ----------

    private enum class Screen { Library, Player }

    data class BookRow(
        val book: Book,
        val progress: Float, // 0..1
        val remainingMs: Long,
        val started: Boolean,
        val finished: Boolean,
        val updatedAt: Long,
    )

    @Composable
    private fun BooksApp() {
        var screen by remember { mutableStateOf(Screen.Library) }

        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!storageGranted) {
            PermissionGate()
            return
        }
        when (screen) {
            Screen.Library -> LibraryScreen(openPlayer = { screen = Screen.Player })
            Screen.Player -> PlayerScreen(back = { screen = Screen.Library })
        }
    }

    @Composable
    private fun PermissionGate() {
        val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            storageGranted = canReadBooks()
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Books", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                "Storage access is needed to read audiobooks from /sdcard/Audiobooks.",
                fontSize = 18.sp, lineHeight = 26.sp,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }) { Text("Grant all-files access", fontSize = 18.sp) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { audioLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) }) {
                Text("Grant audio access only", fontSize = 18.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Or via adb:\nappops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow",
                fontSize = 13.sp, color = Color.Gray, lineHeight = 18.sp,
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun LibraryScreen(openPlayer: () -> Unit) {
        var rows by remember { mutableStateOf<List<BookRow>?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var dialogBook by remember { mutableStateOf<Book?>(null) }

        // Rescan on entry and every 5s (durations are cached after the first pass, so this
        // is cheap; it keeps the playing book's progress line moving).
        LaunchedEffect(refresh) {
            while (isActive) {
                rows = withContext(Dispatchers.IO) { buildRows() }
                delay(5_000)
            }
        }

        Column(Modifier.fillMaxSize()) {
            Text(
                "Books",
                fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp),
            )

            val list = rows
            if (list == null) {
                Text("Scanning…", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.padding(20.dp))
            } else if (list.isEmpty()) {
                Column(Modifier.padding(20.dp)) {
                    Text("No audiobooks found.", fontSize = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Push books to /sdcard/Audiobooks — a single file, or a folder of files (sorted by name) per book:\n\nadb push MyBook/ /sdcard/Audiobooks/MyBook/",
                        fontSize = 15.sp, color = Color.Gray, lineHeight = 22.sp,
                    )
                }
            } else {
                val continueRow = list.filter { it.started && !it.finished }.maxByOrNull { it.updatedAt }
                LazyColumn(Modifier.weight(1f)) {
                    if (continueRow != null) {
                        item(key = "continue") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playBook(continueRow.book.id)
                                        openPlayer()
                                    }
                                    .padding(20.dp, 14.dp),
                            ) {
                                Text("CONTINUE", fontSize = 13.sp, color = Color.Gray, letterSpacing = 2.sp)
                                Text(
                                    "▶  " + continueRow.book.title,
                                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(statusLine(continueRow), fontSize = 15.sp, color = Color.Gray)
                            }
                        }
                    }
                    items(list, key = { it.book.id }) { row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        playBook(row.book.id)
                                        openPlayer()
                                    },
                                    onLongClick = { dialogBook = row.book },
                                )
                                .padding(20.dp, 14.dp),
                        ) {
                            Text(
                                row.book.title,
                                fontSize = 20.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(statusLine(row), fontSize = 15.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // Mini "now playing" bar.
            val c = controller
            if (c != null && itemCount > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openPlayer() }
                        .padding(20.dp, 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (nowPlaying?.title ?: "Now playing").toString(),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        if (isPlaying) "⏸" else "▶",
                        fontSize = 26.sp,
                        modifier = Modifier.clickable { if (isPlaying) c.pause() else c.play() },
                    )
                }
            }
        }

        dialogBook?.let { book ->
            AlertDialog(
                onDismissRequest = { dialogBook = null },
                containerColor = Color.Black,
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text(book.title) },
                text = { Text("Reset this book's saved position?") },
                confirmButton = {
                    TextButton(onClick = {
                        PositionStore(this@MainActivity).clear(book.id)
                        dialogBook = null
                        refresh++
                    }) { Text("Restart from beginning", color = Color.White) }
                },
                dismissButton = {
                    TextButton(onClick = { dialogBook = null }) { Text("Cancel", color = Color.Gray) }
                },
            )
        }
    }

    private fun buildRows(): List<BookRow> {
        val store = PositionStore(this)
        val durations = DurationCache(this)
        return Library.scan().map { book ->
            val saved = store.load(book.id)
            val fileMs = durations.fileDurations(book)
            val total = fileMs.sum()
            val listened = if (saved == null) 0L else {
                fileMs.take(saved.fileIndex.coerceIn(0, fileMs.size)).sum() + saved.posMs
            }
            val effTotal = if (total > 0) total else 1L
            BookRow(
                book = book,
                progress = (listened.toFloat() / effTotal).coerceIn(0f, 1f),
                remainingMs = (total - listened).coerceAtLeast(0L),
                started = saved != null && !saved.finished && listened > 0,
                finished = saved?.finished == true,
                updatedAt = saved?.updatedAt ?: 0L,
            )
        }
    }

    private fun statusLine(row: BookRow): String = when {
        row.finished -> "Finished ✓"
        !row.started -> "Not started · " + formatDuration(row.remainingMs)
        else -> "${(row.progress * 100).toInt()}% · ${formatDuration(row.remainingMs)} left"
    }

    @Composable
    private fun PlayerScreen(back: () -> Unit) {
        val c = controller
        if (c == null || itemCount == 0) {
            LaunchedEffect(Unit) { back() }
            return
        }

        var posMs by remember { mutableStateOf(0L) }
        var durMs by remember { mutableStateOf(0L) }
        var dragging by remember { mutableStateOf(false) }
        var dragPos by remember { mutableStateOf(0f) }
        val sleepEndsAt by PlaybackService.sleepEndsAt.collectAsState()
        var now by remember { mutableStateOf(System.currentTimeMillis()) }

        LaunchedEffect(c) {
            while (isActive) {
                if (!dragging) {
                    posMs = c.currentPosition.coerceAtLeast(0L)
                    durMs = c.duration.coerceAtLeast(0L)
                }
                now = System.currentTimeMillis()
                delay(500)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "‹ Library", fontSize = 18.sp, color = Color.Gray,
                    modifier = Modifier.clickable { back() }.padding(4.dp),
                )
            }
            Spacer(Modifier.weight(1f))

            Text(
                (nowPlaying?.title ?: "").toString(),
                fontSize = 26.sp, fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                (nowPlaying?.artist ?: "").toString(),
                fontSize = 16.sp, color = Color.Gray,
            )

            Spacer(Modifier.height(24.dp))

            // Seek within the current file.
            val sliderMax = durMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = if (dragging) dragPos else posMs.toFloat().coerceIn(0f, sliderMax),
                valueRange = 0f..sliderMax,
                onValueChange = { dragging = true; dragPos = it },
                onValueChangeFinished = {
                    c.seekTo(dragPos.toLong())
                    posMs = dragPos.toLong()
                    dragging = false
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(if (dragging) dragPos.toLong() else posMs), fontSize = 14.sp, color = Color.Gray)
                Text(formatDuration(durMs), fontSize = 14.sp, color = Color.Gray)
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton("⏮", enabled = itemCount > 1) { c.seekToPreviousMediaItem() }
                TransportButton("−30") { c.seekBack() }
                TransportButton(if (isPlaying) "⏸" else "▶", big = true) {
                    if (isPlaying) c.pause() else c.play()
                }
                TransportButton("+30") { c.seekForward() }
                TransportButton("⏭", enabled = itemCount > 1) { c.seekToNextMediaItem() }
            }

            if (itemCount > 1) {
                Spacer(Modifier.height(12.dp))
                Text("Part ${itemIndex + 1} of $itemCount", fontSize = 15.sp, color = Color.Gray)
            }

            Spacer(Modifier.height(28.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = {
                    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                    val next = speeds[(speeds.indexOfFirst { it >= speed - 0.01f } + 1) % speeds.size]
                    c.playbackParameters = PlaybackParameters(next)
                }) { Text("${trimZero(speed)}×", fontSize = 17.sp) }

                OutlinedButton(onClick = {
                    // Cycle: off → 15 → 30 → 45 → 60 → off
                    val remainingMin = ((sleepEndsAt - now) / 60_000L).toInt()
                    val next = when {
                        sleepEndsAt == 0L -> 15L
                        remainingMin < 15 -> 15L // was running low; treat as off→15 for simplicity
                        remainingMin < 30 -> 30L
                        remainingMin < 45 -> 45L
                        remainingMin < 60 -> 60L
                        else -> 0L
                    }
                    // A second tap while a timer runs bumps it to the next step; from 60, off.
                    setSleep(if (sleepEndsAt != 0L && remainingMin >= 60) 0L else next * 60_000L)
                }) {
                    Text(
                        if (sleepEndsAt > now) "☾ ${formatDuration(sleepEndsAt - now)}" else "☾ off",
                        fontSize = 17.sp,
                    )
                }
            }

            Spacer(Modifier.weight(1.4f))
        }
    }

    @Composable
    private fun TransportButton(label: String, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
        Text(
            label,
            fontSize = if (big) 46.sp else 24.sp,
            color = if (enabled) Color.White else Color.DarkGray,
            modifier = Modifier
                .clickable(enabled = enabled) { onClick() }
                .padding(10.dp),
        )
    }

    private fun trimZero(f: Float): String =
        if (f % 1f == 0f) f.toInt().toString() else f.toString().trimEnd('0').trimEnd('.')

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
