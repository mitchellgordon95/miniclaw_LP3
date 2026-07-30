package ai.mytextpal.miniclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlin.concurrent.thread

/**
 * Foreground service hosting the MediaSession that owns the earbud taps ONLY while voice input
 * is being recorded (MainActivity toggles it via [setSessionActive]): 1 tap = confirm & send,
 * 2–3 taps = discard. The rest of the time — including while the reply is speaking — the
 * session is inactive so taps keep their normal media meaning (podcast/audiobook control).
 * Summoning, and interrupting a reply, is the buds' 5-tap voice-assistant gesture, which
 * arrives as a voice-assistant intent on MainActivity — not through here.
 *
 * How taps reach us mid-recording even locked/screen-off: media transport buttons are routed by
 * the framework to the "active media session of the app that most recently played audio
 * locally", regardless of screen/keyguard state. So on recording start we (1) activate our
 * MediaSession, and (2) claim that most-recently-played slot with a brief *silent* AudioTrack
 * blip.
 *
 * [IDLE_TAP_SUMMON] is the pre-5-tap behavior — the session stays active and claims routing
 * permanently, so a single tap summons from idle. Flip it back on if the Raycons' 5-tap gesture
 * turns out not to reach Android as a voice-assistant intent.
 */
class WakeService : Service() {

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var deviceCallback: AudioDeviceCallback? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        startForegroundNotification()
        setupSession()
        if (IDLE_TAP_SUMMON) {
            session.isActive = true
            claimRouting() // become the most-recent player now
            registerDeviceCallback() // …and again whenever earbuds (re)connect
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SESSION_ACTIVE -> {
                session.isActive = true
                claimRouting()
            }
            ACTION_SESSION_IDLE -> if (!IDLE_TAP_SUMMON) session.isActive = false
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        try { session.isActive = false; session.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // --- MediaSession: receive the earbud button ---

    private fun setupSession() {
        session = MediaSessionCompat(this, "MiniClawWake").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ke = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ke != null && ke.action == KeyEvent.ACTION_DOWN) {
                        when {
                            isPrimary(ke.keyCode) -> {
                                Log.d(TAG, "media button ${ke.keyCode} → fire")
                                Summon.fire(this@WakeService); return true
                            }
                            // Raycon gestures: double tap = "next track", triple tap =
                            // "previous track" — both are our abort/cancel gesture (a miscounted
                            // extra tap should still read as "back out").
                            ke.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                                ke.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                Log.d(TAG, "media button ${ke.keyCode} → cancel")
                                Summon.cancel(this@WakeService); return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                // Some controllers/headsets call these directly instead of sending a key event.
                override fun onPlay() = Summon.fire(this@WakeService)
                override fun onPause() = Summon.fire(this@WakeService)
                override fun onSkipToNext() = Summon.cancel(this@WakeService)
                override fun onSkipToPrevious() = Summon.cancel(this@WakeService)
            })

            // Report PLAYING so we sort to the top of active sessions for button routing.
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .build(),
            )
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Voice")
                    .build(),
            )
            // isActive is toggled per voice session (or permanently under IDLE_TAP_SUMMON).
        }
    }

    private fun isPrimary(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> true
        else -> false
    }

    // --- Routing claim: a brief silent blip makes us the "most recent player" ---

    private fun claimRouting() {
        thread(name = "wake-blip") {
            try {
                val sampleRate = 8000
                val durMs = 200
                val frames = sampleRate * durMs / 1000
                val silence = ShortArray(frames) // zeros
                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    silence.size * 2,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
                track.play()
                track.write(silence, 0, silence.size)
                Thread.sleep((durMs + 60).toLong())
                track.stop()
                track.release()
                Log.d(TAG, "routing blip played")
            } catch (e: Exception) {
                Log.w(TAG, "routing blip failed", e)
            }
        }
    }

    private fun registerDeviceCallback() {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (added.any { it.type in BLUETOOTH_OUT_TYPES }) {
                    Log.d(TAG, "bluetooth output connected → reclaim routing")
                    claimRouting()
                }
            }
        }
        deviceCallback = cb
        audioManager.registerAudioDeviceCallback(cb, main)
    }

    // --- Foreground notification ---

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice wake", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Voice ready")
            .setContentText("Tap an earbud 5 times to talk")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "Wake"
        private const val CHANNEL = "wake"
        private const val NOTIF_ID = 42

        // Fallback: single tap summons from idle (the session hijacks taps permanently).
        private const val IDLE_TAP_SUMMON = false

        private const val ACTION_SESSION_ACTIVE = "ai.mytextpal.miniclaw.SESSION_ACTIVE"
        private const val ACTION_SESSION_IDLE = "ai.mytextpal.miniclaw.SESSION_IDLE"

        /** MainActivity calls this as a voice session starts/ends to grab/release the taps. */
        fun setSessionActive(context: Context, active: Boolean) {
            context.startService(
                Intent(context, WakeService::class.java)
                    .setAction(if (active) ACTION_SESSION_ACTIVE else ACTION_SESSION_IDLE),
            )
        }

        private val BLUETOOTH_OUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
