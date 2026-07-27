# Books — audiobook player for the LP3

A sideloaded audiobook player that **remembers your position in every book** (the stock
LightOS music player restarts long files from zero every time, which makes audiobooks
unusable). Built as a normal Android APK because the official LightOS SDK has no media
APIs and no foreground services — an audiobook tool can't be built inside its sandbox.

## Features

- **Persistent position per book** — saved every 5 s while playing, plus on pause, part
  change, and service shutdown. Survives reboots and reinstalls.
- **Smart resume** — rewinds a little on resume for context (10 s if you've been away
  under an hour, 20 s under a day, 30 s beyond that).
- **Background playback** — Media3 foreground service with a media notification; keeps
  playing with the screen off.
- **Multi-file books** — a folder is a book; files play in natural sort order
  (`2.mp3` before `10.mp3`) with auto-advance. A single file is also a book.
- Per-book **playback speed** (0.75–2×, remembered), **sleep timer** (15/30/45/60 min),
  ±30 s skip, part prev/next, seek slider, library progress lines
  ("42% · 3:12:05 left"), and a "Continue" shortcut for the most recent book.
- Long-press a book in the library to reset its position.

## Getting books on the phone

```
adb push "My Book.m4b" /sdcard/Audiobooks/                 # single-file book
adb push "My Series Book 1/" "/sdcard/Audiobooks/My Series Book 1/"   # folder book
```

Supported: mp3, m4a, m4b, aac, ogg, opus, flac, wav, mka.

### Audible

There is no on-device Audible integration: Audible has no public playback API and its
downloads are DRM-locked (and the LP3 has no Google services to run the Audible app).
The practical path for books you own is [Libation](https://github.com/rmcrackan/Libation)
on the Mac — it downloads your own Audible library as plain `.m4b` files for personal
backup — then `adb push` them as above.

## Build + install

```
./gradlew :audiobook:assembleDebug
adb install -r audiobook/build/outputs/apk/debug/audiobook-debug.apk
adb shell pm grant ai.mytextpal.audiobook android.permission.POST_NOTIFICATIONS
adb shell pm grant ai.mytextpal.audiobook android.permission.READ_MEDIA_AUDIO
# Recommended: all-files access avoids MediaStore scan-lag/indexing quirks for pushed files
adb shell appops set --uid ai.mytextpal.audiobook MANAGE_EXTERNAL_STORAGE allow
```

(Both grant buttons also exist in-app on first launch.)

## Known interaction with MiniClaw's earbud wake

While a book is playing (or paused less than ~10 min), the Pixel Buds' tap is the
book's play/pause — Android routes media buttons to the most recent audio player, so
MiniClaw's wake tap won't fire. Ten minutes after you pause, the player releases its
media session and the button falls back to MiniClaw's wake service (which also
re-claims it whenever the buds reconnect). While actively listening, summon MiniClaw
with the DJI button or by opening the app instead.
