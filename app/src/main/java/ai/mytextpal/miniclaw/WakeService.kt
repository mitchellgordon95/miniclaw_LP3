package ai.mytextpal.miniclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
 * Always-on foreground service whose only job is to catch a Bluetooth earbud's media button
 * (e.g. a single tap on Pixel Buds → AVRCP PLAY/PAUSE) while the phone is locked and the screen
 * is off — the one input that the DJI volume-up button can't deliver from a pocket.
 *
 * How it reaches us when locked: media transport buttons are routed by the framework to the
 * "active media session of the app that most recently played audio locally", regardless of
 * screen/keyguard state. So we (1) hold an active MediaSession, and (2) claim that
 * most-recently-played slot with a brief *silent* AudioTrack blip — on start and whenever a
 * Bluetooth output device connects. We deliberately do NOT loop silence continuously: the
 * "most recent player" status is sticky until some other app plays, so an occasional blip keeps
 * us the button target while letting the phone sleep normally (battery-minimal).
 *
 * On a press we don't run audio here — we just wake the screen and launch MainActivity (which is
 * flagged show-when-locked); the existing record→transcribe→reply loop runs there, so the
 * Activity is "in use" and mic capture is unrestricted.
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
        claimRouting() // become the most-recent player now
        registerDeviceCallback() // …and again whenever earbuds (re)connect
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
                            // Double tap on the earbuds = "next track" → our abort/cancel gesture.
                            ke.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                Log.d(TAG, "media button NEXT → cancel")
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
            })

            // Report PLAYING so we sort to the top of active sessions for button routing.
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .build(),
            )
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Voice")
                    .build(),
            )
            isActive = true
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
            .setContentText("Tap your earbud to talk")
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

        private val BLUETOOTH_OUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
