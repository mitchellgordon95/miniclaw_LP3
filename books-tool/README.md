# Books — an audiobook player for the Light Phone III

A LightOS **tool** built on Light's official SDK. It remembers your position in every
book, which the stock Music tool doesn't (it restarts long files from zero every time).

## Features

- **Persistent position per book** — saved every 5 s while playing, plus on pause, part
  change, and when the player idles out. Survives reboots and reinstalls.
- **Smart resume** — rewinds a little when you come back for context: 10 s if you've been
  away under an hour, 20 s under a day, 30 s beyond that. Nothing under a minute.
- **Background playback** through the SDK's detached audio service: keeps playing with the
  screen off and after you leave the tool. Earbud buttons control it.
- **Multi-part books** — a folder is a book; files play in natural sort order (`2.mp3`
  before `10.mp3`) with auto-advance. A single file is also a book.
- Per-book **playback speed** (0.75–2×, remembered), **sleep timer** (15/30/45/60 min),
  ±15 s skip, part prev/next, tap-to-seek progress bar, and library progress lines
  ("42% · 3:12:05 left") with a **Continue** shortcut for the most recent book.
- **Restart** a book from the player screen (tap twice).

## Getting books on the phone

Books live in the phone's shared `Audiobooks` folder. Supported: mp3, m4a, m4b, aac, ogg,
opus, flac, wav, mka.

```
adb push "My Book.m4b" /sdcard/Audiobooks/                          # single-file book
adb push "My Series Book 1/" "/sdcard/Audiobooks/My Series Book 1/"   # folder book
```

The tool asks LightOS for audio-file access the first time (READ_MEDIA_AUDIO, which is
on the SDK's permission allowlist). When Light's Tool Manager reaches production builds,
it will be the no-cable way to load files.

### Audible

No on-device Audible: its downloads are DRM-locked and there is no public API. For books
you own, [Libation](https://github.com/rmcrackan/Libation) on the Mac exports your
library as plain `.m4b` files, then `adb push` them as above.

## Building

Light's SDK comes in as the `light-sdk` git submodule; Books is the `tool/` module built
against it, which is the shape a LightOS tool takes.

```
git submodule update --init
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
```

The APK lands in `tool/build/outputs/apk/debug/`. It is signed with the SDK's shared
development key, which is fine for side-loading your own builds.

### Installing today (ADB)

```
adb install -r tool/build/outputs/apk/debug/tool-debug.apk
```

`lighttool.toml` binds the tool to real LightOS (`serverPackage = "com.lightos"`). For
the LightOS emulator, switch it to `com.thelightphone.sdk.emulator`.

### Installing later (Tool Manager)

Once the SDK's Tool Manager ships in LightOS: enable developer mode in the Light
dashboard, start the Tool Manager on the phone, and either upload the APK from a browser
or run `./gradlew :tool:uploadTool -Pdevice.ip=<phone ip> -Pdevice.token=<key>`. See the
SDK's `docs/sideloading` when it lands.

## How the pieces fit

| File | Role |
| --- | --- |
| `Rules.kt` | Pure, unit-tested logic: natural sort, resume rewind, finished detection, speed/sleep cycles, progress math |
| `Library.kt` | Scans `/sdcard/Audiobooks`; asks LightOS about the media permission; reads durations |
| `PositionStore.kt` | Positions and duration cache in the SDK's DataStore |
| `Playback.kt` | The process-wide player on the SDK's detached audio service; saves position, sleep timer, idle release |
| `HomeScreen.kt` | Library list with Continue row and mini now-playing bar |
| `PlayerScreen.kt` | Now playing: progress, transport, speed, sleep, restart |

The player handle is released ten minutes after a pause so the SDK service can idle out
and the earbuds' media button falls back to whatever played before; play reconnects.
