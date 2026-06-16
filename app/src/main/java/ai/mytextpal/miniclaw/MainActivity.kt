package ai.mytextpal.miniclaw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class Exchange(val you: String, val wright: String)

/**
 * MiniClaw launcher (v1).
 *
 * DJI button / on-screen mic → record → transcribe → send over WebSocket → Wright's reply
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
    private var summonEnabled by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private val history = mutableStateListOf<Exchange>()

    private var pendingTranscript = ""
    private var pendingTrigger = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else status = "mic permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
            onActiveChange = { active -> runOnUiThread { speaking = active } },
        )

        pendingTrigger = intent?.getBooleanExtra(EXTRA_TRIGGER, false) == true

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
                        listening = recording,
                        active = busy || speaking,
                        status = status,
                        summonEnabled = summonEnabled,
                        onMic = ::onTrigger,
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
        if (intent.getBooleanExtra(EXTRA_TRIGGER, false)) pendingTrigger = true
    }

    override fun onResume() {
        super.onResume()
        Summon.activityResumed = true
        Summon.activityTrigger = { onTrigger() }
        summonEnabled = isSummonServiceEnabled()
        if (pendingTrigger) {
            pendingTrigger = false
            onTrigger()
        }
    }

    override fun onPause() {
        Summon.activityResumed = false
        Summon.activityTrigger = null
        super.onPause()
    }

    override fun onDestroy() {
        client.close()
        ttsPlayer.stop()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) onTrigger()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onTrigger() {
        if (recorder.isRecording) {
            stopAndSend()
            return
        }
        if (busy || ttsPlayer.isSpeaking) {
            // Something is generating or speaking: stop it; do NOT start a new recording.
            client.abort()
            ttsPlayer.stop()
            ttsBuffer.setLength(0)
            busy = false
            speaking = false
            status = ""
            return
        }
        if (hasAudioPermission()) startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        ttsPlayer.stop()
        ttsBuffer.setLength(0)
        speaking = false
        if (recorder.start()) {
            recording = true
            status = "listening"
        } else {
            status = "mic error"
        }
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
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { client.transcribe(file) }
            Log.d("Perf", "transcribe=${SystemClock.elapsedRealtime() - tStop}ms")
            if (text.isNullOrBlank()) {
                status = "couldn't hear that"
                busy = false
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

    private fun isSummonServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/.SummonService") ||
            enabled.contains("$packageName/$packageName.SummonService")
    }

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
    }

    companion object {
        const val EXTRA_TRIGGER = "trigger"
    }
}

@Composable
private fun HomeScreen(
    listening: Boolean,
    active: Boolean,
    status: String,
    summonEnabled: Boolean,
    onMic: () -> Unit,
    onHistory: () -> Unit,
    onReturnHome: () -> Unit,
) {
    val accent = Color(0xFF7CFFB2)
    val stopColor = Color(0xFFFF8A80)
    val muted = Color(0xFF9AA0A6)

    val stopping = active && !listening
    val circleBg = when {
        listening -> Color(0xFF13351F)
        stopping -> Color(0xFF3A1313)
        else -> Color(0xFF1A1A1A)
    }
    val title = when {
        listening -> "Listening…"
        stopping -> "Tap to stop"
        else -> "Tap to talk"
    }
    val titleColor = when {
        listening -> accent
        stopping -> stopColor
        else -> Color.White
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

            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(circleBg)
                    .clickable { onMic() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (stopping) "⏹" else "🎤", fontSize = 60.sp)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                color = titleColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(status, color = muted, fontSize = 14.sp)

            Spacer(Modifier.weight(1f))

            if (!summonEnabled) {
                Text(
                    "Summon off — enable “MiniClaw mic button” in Accessibility",
                    color = Color(0xFFB0884A),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
            }
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
