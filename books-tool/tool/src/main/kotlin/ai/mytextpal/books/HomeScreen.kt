package ai.mytextpal.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BookRow(
    val book: Book,
    val saved: SavedPosition?,
    val progress: BookProgress,
) {
    val started: Boolean get() = saved != null && !saved.finished && progress.listenedMs > 0L
    val status: String get() = statusLine(progress, saved)
}

class HomeViewModel(private val store: PositionStore) : LightViewModel<Unit>() {

    /** null = not scanned yet. */
    val rows = MutableStateFlow<List<BookRow>?>(null)
    val access = MutableStateFlow(Library.Access.UNKNOWN)
    private var refreshJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // Rescan while visible; durations are cached after the first pass, so this is
        // cheap and it keeps the playing book's progress line moving.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            access.value = Library.access()
            while (isActive) {
                refresh()
                delay(5_000L)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun recheckAccess() {
        viewModelScope.launch {
            access.value = Library.access()
            refresh()
        }
    }

    private suspend fun refresh() {
        val positions = store.loadAll()
        rows.value = Library.scan().map { book ->
            val saved = positions[book.id]
            BookRow(book, saved, bookProgress(store.partDurations(book), saved))
        }
    }
}

@InitialScreen
class HomeScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel {
        val store = PositionStore(lightContext.dataStore)
        Playback.start(DefaultLightAudio(sealedActivity), store)
        return HomeViewModel(store)
    }

    private fun openBook(book: Book) {
        Playback.open(book)
        navigateTo(::PlayerScreen)
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val rows by viewModel.rows.collectAsState()
        val access by viewModel.access.collectAsState()
        val current by Playback.book.collectAsState()
        val playing by Playback.isPlaying.collectAsState()
        val fault by Playback.fault.collectAsState()
        val permission = rememberPermissionRequestLauncher(Library.PERMISSION)

        LightTheme(colors = colors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text("Books"))

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    val list = rows
                    if (access == Library.Access.NOT_GRANTED || access == Library.Access.BLOCKED_BY_LIGHTOS) {
                        MenuRow(
                            title = "ALLOW AUDIOBOOK ACCESS",
                            subtitle = if (access == Library.Access.BLOCKED_BY_LIGHTOS) {
                                "LightOS is blocking it for this tool"
                            } else {
                                "Needed to read the phone's Audiobooks folder"
                            },
                            onClick = {
                                permission?.launch()
                                viewModel.recheckAccess()
                            },
                        )
                    }
                    fault?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                        )
                    }
                    when {
                        list == null -> LightText(
                            text = "Scanning…",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                        list.isEmpty() -> EmptyLibrary()
                        else -> {
                            val continueRow = list.filter { it.started }.maxByOrNull { it.saved?.updatedAt ?: 0L }
                            if (continueRow != null) {
                                MenuRow(
                                    title = "CONTINUE",
                                    subtitle = continueRow.book.title,
                                    detail = continueRow.status,
                                    onClick = { openBook(continueRow.book) },
                                )
                            }
                            list.forEach { row ->
                                MenuRow(
                                    title = row.book.title,
                                    subtitle = row.status,
                                    onClick = { openBook(row.book) },
                                )
                            }
                        }
                    }
                }

                current?.let { book ->
                    LightText(
                        text = (if (playing) "PLAYING  " else "PAUSED  ") + book.title,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { navigateTo(::PlayerScreen) }
                            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    )
                    LightBottomBar(
                        items = listOf(
                            LightBarButton.LightIcon(
                                if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                                Playback::togglePlayPause,
                            ),
                            LightBarButton.LightIcon(
                                LightIcons.MEDIA,
                                { navigateTo(::PlayerScreen) },
                                contentDescription = "Now playing",
                            ),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(Modifier.padding(vertical = 0.75f.gridUnitsAsDp())) {
        LightText(text = "No audiobooks found.", variant = LightTextVariant.Copy)
        LightText(
            text = "Put books in the phone's Audiobooks folder: one file per book, or a folder of parts named in order.",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
        LightText(
            text = "adb push MyBook/ /sdcard/Audiobooks/MyBook/",
            variant = LightTextVariant.Fine,
            lighten = true,
            monospace = true,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String? = null,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    ) {
        LightText(text = title, variant = LightTextVariant.Copy, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let {
            LightText(text = it, variant = LightTextVariant.Fine, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        detail?.let {
            LightText(text = it, variant = LightTextVariant.Fine, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
