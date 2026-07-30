package ai.mytextpal.miniclaw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.speech.RecognizerIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class Exchange(val you: String, val wright: String)

/** The three interaction states that drive the on-screen controls. */
private enum class UiMode { IDLE, RECORDING, RESPONDING }

/**
 * MiniClaw launcher (v1).
 *
 * Earbud tap / on-screen mic → record → transcribe → send over WebSocket → Wright's reply
 * streams back token-by-token and is spoken sentence-by-sentence (cloud TTS via /api/tts, with
 * the on-device engine as fallback). Speaking starts as soon as the first sentence arrives.
 */
class MainActivity : ComponentActivity(), MiniClawListener {

    private lateinit var recorder: VoiceRecorder
    private lateinit var client: MiniClawClient
    private lateinit var ttsPlayer: TtsPlayer
    private var tts: TextToSpeech? = null

    // Streaming-TTS sentence buffer.
    private val ttsBuffer = StringBuilder()

    // Latency instrumentation (logcat tag "Perf").
    private var tStop = 0L
    private var tSent = 0L
    private var sawFirstDelta = false

    // UI state
    private var recording by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var showHistory by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private val history = mutableStateListOf<Exchange>()

    private var pendingTranscript = ""
    private var pendingTrigger = false

    // True while VoiceRecorder is bringing up the Bluetooth mic link (~1s); taps are ignored
    // during this window so a stray press can't stop a recorder that hasn't started yet.
    private var starting = false

    // Whether we currently own the earbud tap routing (see syncSessionClaim).
    private var sessionClaimed = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else status = "mic permission denied"
    }

    private var toneGen: ToneGenerator? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the wake service runs regardless; this just lets its notification show */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Show over the keyguard and power the screen on when summoned from a locked pocket.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Start (and keep alive) the earbud-button wake service, and make sure its FGS
        // notification can be shown on Android 13+.
        ContextCompat.startForegroundService(this, Intent(this, WakeService::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        recorder = VoiceRecorder(this)
        client = MiniClawClient(
            baseUrl = BuildConfig.MINICLAW_BASE_URL,
            wsUrl = BuildConfig.MINICLAW_WS_URL,
            token = BuildConfig.MINICLAW_TOKEN,
            listener = this,
        )
        client.connect()

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        ttsPlayer = TtsPlayer(
            context = this,
            scope = lifecycleScope,
            synth = { text -> client.synthesize(text) },
            androidTts = tts!!,
            onActiveChange = { active -> runOnUiThread { speaking = active; syncSessionClaim() } },
        )

        pendingTrigger = isTriggerIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showHistory) showHistory = false
            }
        })

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (showHistory) {
                    HistoryScreen(history = history, onBack = { showHistory = false })
                } else {
                    HomeScreen(
                        mode = when {
                            recording -> UiMode.RECORDING
                            busy || speaking -> UiMode.RESPONDING
                            else -> UiMode.IDLE
                        },
                        status = status,
                        onPrimary = ::onPrimary,
                        onAbort = ::onAbort,
                        onHistory = { showHistory = true },
                        onReturnHome = ::returnToLightOS,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isTriggerIntent(intent)) pendingTrigger = true
    }

    /**
     * Launches that should start listening immediately: our own trigger extra (WakeService), or
     * the system's voice-assistant intents — which is how the earbuds' 5-tap gesture arrives.
     */
    private fun isTriggerIntent(intent: Intent?): Boolean =
        intent != null && (
            intent.getBooleanExtra(EXTRA_TRIGGER, false) ||
                intent.action == Intent.ACTION_VOICE_COMMAND ||
                intent.action == RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE
            )

    override fun onResume() {
        super.onResume()
        Summon.activityResumed = true
        Summon.activityPrimary = { onPrimary() }
        Summon.activityAbort = { onAbort() }
        if (pendingTrigger) {
            pendingTrigger = false
            onPrimary()
        }
    }

    override fun onPause() {
        Summon.activityResumed = false
        Summon.activityPrimary = null
        Summon.activityAbort = null
        super.onPause()
    }

    override fun onDestroy() {
        client.close()
        ttsPlayer.stop()
        tts?.stop()
        tts?.shutdown()
        toneGen?.release()
        super.onDestroy()
    }

    /**
     * Single tap / "advance the loop":
     *   idle → start recording; recording → confirm & send; responding → stop the reply early
     *   and immediately start listening again.
     */
    private fun onPrimary() {
        when {
            starting -> {} // mic link still coming up — ignore
            recorder.isRecording -> stopAndSend()
            busy || ttsPlayer.isSpeaking -> { abortReply(); beginRecording() }
            else -> beginRecording()
        }
    }

    /**
     * Double or triple tap / "back out":
     *   recording → discard it; responding → stop the reply and stay idle; idle → nothing.
     */
    private fun onAbort() {
        when {
            starting -> recording = false // flag the in-flight start to bail out
            recorder.isRecording -> {
                recorder.cancel()
                recording = false
                status = ""
            }
            busy || ttsPlayer.isSpeaking -> abortReply()
        }
        syncSessionClaim()
    }

    /** Stop an in-flight generation and any speech, returning to idle. */
    private fun abortReply() {
        client.abort()
        ttsPlayer.stop()
        ttsBuffer.setLength(0)
        busy = false
        speaking = false
        status = ""
        syncSessionClaim()
    }

    /**
     * Own the earbud tap routing ONLY while actually recording: a single tap then ends voice
     * input (confirm & send) and 2–3 taps discard it. Any other time — idle, thinking, even
     * while the reply is speaking — taps are not caught at all and fall through to the podcast
     * player. Summoning (and interrupting a reply) is the 5-tap voice-assistant gesture.
     */
    private fun syncSessionClaim() {
        val active = recording
        if (active != sessionClaimed) {
            sessionClaimed = active
            WakeService.setSessionActive(this, active)
        }
    }

    private fun beginRecording() {
        if (hasAudioPermission()) startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        if (starting || recorder.isRecording) return
        starting = true
        ttsPlayer.stop()
        ttsBuffer.setLength(0)
        speaking = false
        recording = true
        status = "mic…"
        syncSessionClaim()
        lifecycleScope.launch {
            val ok = recorder.start() // suspends ~1s while the earbud SCO link comes up
            starting = false
            if (!recording) { // aborted while the link was coming up
                if (ok) recorder.cancel()
                syncSessionClaim()
                return@launch
            }
            if (ok) {
                status = "listening"
                playReady()
            } else {
                recording = false
                status = "mic error"
                playError()
                syncSessionClaim()
            }
        }
    }

    /** Short "listening" beep, routed so it's audible in the earbuds while their mic is live. */
    private fun playReady() {
        try {
            val stream =
                if (recorder.usingBluetoothMic) AudioManager.STREAM_VOICE_CALL
                else AudioManager.STREAM_MUSIC
            val tg = ToneGenerator(stream, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP)
            lifecycleScope.launch { delay(300); tg.release() }
        } catch (_: Exception) {}
    }

    /** Short error tone (routes through STREAM_MUSIC → the earbuds). */
    private fun playError() {
        try {
            val tg = toneGen ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { toneGen = it }
            tg.startTone(ToneGenerator.TONE_PROP_NACK)
        } catch (_: Exception) {}
    }

    private fun stopAndSend() {
        recording = false
        busy = true
        status = "transcribing…"
        tStop = SystemClock.elapsedRealtime()
        val file = recorder.stop()
        if (file == null) {
            status = "mic error"
            busy = false
            syncSessionClaim()
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { client.transcribe(file) }
            Log.d("Perf", "transcribe=${SystemClock.elapsedRealtime() - tStop}ms")
            if (text.isNullOrBlank()) {
                status = "couldn't hear that"
                busy = false
                syncSessionClaim()
                return@launch
            }
            pendingTranscript = text
            status = "thinking…"
            sawFirstDelta = false
            ttsBuffer.setLength(0)
            tSent = SystemClock.elapsedRealtime()
            if (!client.sendText(text)) {
                client.connect()
                if (!client.sendText(text)) {
                    status = "not connected"
                    busy = false
                    syncSessionClaim()
                }
            }
        }
    }

    /** Pull complete sentences off the streaming buffer and hand them to the player. */
    private fun feedTts(delta: String) {
        ttsBuffer.append(delta)
        while (true) {
            val s = ttsBuffer.toString()
            val idx = sentenceEnd(s)
            if (idx < 0) break
            ttsPlayer.enqueue(s.substring(0, idx + 1))
            ttsBuffer.delete(0, idx + 1)
        }
    }

    private fun sentenceEnd(s: String): Int {
        for (i in 0 until s.length - 1) {
            val c = s[i]
            if ((c == '.' || c == '!' || c == '?') && s[i + 1].isWhitespace()) return i
        }
        val nl = s.indexOf('\n')
        return nl
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun returnToLightOS() {
        val i = packageManager.getLaunchIntentForPackage("com.lightos")
        if (i != null) startActivity(i) else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // --- MiniClawListener (callbacks arrive on background threads) ---

    override fun onStatus(status: String) = runOnUiThread {
        if (status == "connected" || status.startsWith("ws error") || status == "disconnected") {
            if (!busy) this.status = if (status == "connected") "" else status
        } else {
            this.status = status
        }
    }

    override fun onDelta(text: String) = runOnUiThread {
        if (!sawFirstDelta) {
            sawFirstDelta = true
            speaking = true
            Log.d("Perf", "firstToken=${SystemClock.elapsedRealtime() - tSent}ms")
            status = "speaking…"
        }
        feedTts(text)
    }

    override fun onReplyComplete(content: String) = runOnUiThread {
        Log.d(
            "Perf",
            "fullReply=${SystemClock.elapsedRealtime() - tSent}ms total=${SystemClock.elapsedRealtime() - tStop}ms",
        )
        busy = false
        // Flush any trailing partial sentence.
        if (ttsBuffer.isNotBlank()) {
            ttsPlayer.enqueue(ttsBuffer.toString())
            ttsBuffer.setLength(0)
        }
        if (content.isNotBlank()) history.add(Exchange(pendingTranscript, content))
        if (status != "speaking…") status = ""
        pendingTranscript = ""
        syncSessionClaim()
    }

    companion object {
        const val EXTRA_TRIGGER = "trigger"
    }
}

@Composable
private fun HomeScreen(
    mode: UiMode,
    status: String,
    onPrimary: () -> Unit,
    onAbort: () -> Unit,
    onHistory: () -> Unit,
    onReturnHome: () -> Unit,
) {
    val accent = Color(0xFF7CFFB2)
    val stopColor = Color(0xFFFF8A80)
    val muted = Color(0xFF9AA0A6)

    val title = when (mode) {
        UiMode.IDLE -> "Tap to talk"
        UiMode.RECORDING -> "Listening…"
        UiMode.RESPONDING -> "Responding…"
    }
    val titleColor = when (mode) {
        UiMode.IDLE -> Color.White
        UiMode.RECORDING -> accent
        UiMode.RESPONDING -> stopColor
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))

            when (mode) {
                UiMode.IDLE -> CircleButton(
                    glyph = "🎤",
                    bg = Color(0xFF1A1A1A),
                    onClick = onPrimary,
                )
                // Recording: cancel (✗) on the left, confirm & send (✓) on the right.
                UiMode.RECORDING -> Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleButton(glyph = "✕", bg = Color(0xFF3A1313), size = 118.dp, glyphSize = 44.sp, onClick = onAbort)
                    CircleButton(glyph = "✓", bg = Color(0xFF13351F), size = 118.dp, glyphSize = 44.sp, onClick = onPrimary)
                }
                // Responding: one big stop.
                UiMode.RESPONDING -> CircleButton(
                    glyph = "⏹",
                    bg = Color(0xFF3A1313),
                    onClick = onAbort,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(text = title, color = titleColor, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(status, color = muted, fontSize = 14.sp)

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onHistory) { Text("History", color = muted) }
                TextButton(onClick = onReturnHome) { Text("LightOS", color = muted) }
            }
        }
    }
}

@Composable
private fun CircleButton(
    glyph: String,
    bg: Color,
    size: Dp = 150.dp,
    glyphSize: TextUnit = 60.sp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = glyphSize)
    }
}

@Composable
private fun HistoryScreen(history: List<Exchange>, onBack: () -> Unit) {
    val accent = Color(0xFF7CFFB2)
    val muted = Color(0xFF9AA0A6)

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back", color = accent) }
                Text("History", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                if (history.isEmpty()) {
                    Text("No conversations yet.", color = muted, fontSize = 15.sp)
                }
                for (ex in history.asReversed()) {
                    Text("You", color = muted, fontSize = 12.sp)
                    Text(ex.you, color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Wright", color = accent, fontSize = 12.sp)
                    Text(ex.wright, color = Color(0xFFE8EAED), fontSize = 16.sp)
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}
