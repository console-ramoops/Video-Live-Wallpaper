# Video Live Wallpaper

A tiny, battery-conscious video live wallpaper for Android. Pick a video, apply it as your live wallpaper, and the app handles playback, screen state, audio focus, and battery saver — with no ads, analytics, or telemetry.

[![Build APK](https://github.com/console-ramoops/Video-Live-Wallpaper/actions/workflows/build.yaml/badge.svg)](https://github.com/console-ramoops/Video-Live-Wallpaper/actions/workflows/build.yaml)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

## Features

### Video sources

- **System video picker** — choose any video with the standard Android document picker.
- **Manual file paths** — enter an absolute path such as `/storage/emulated/0/Movies/clip.mp4`.
- **`file://` and `content://` URIs** — paste a URI directly from another app or file manager.
- **Remote streams** — open `http`, `https`, and `rtsp` URLs directly in Android's `MediaPlayer`. This covers progressive HTTP/HTTPS and RTSP; HLS, DASH, and RTMP are not supported.
- **Scoped-storage safe** — picked videos are copied into app-private storage immediately, so the wallpaper process can always read them. No broad storage permission is needed for the picker.
- **Automatic reload** — selecting a new video reloads the running wallpaper immediately; no need to re-apply it.

### Playback

- **Loop mode** — play the video continuously.
- **Play-once mode** — play one pass each time the screen turns on.
- **No accidental replays** — leaving the app and returning to the home screen resumes playback instead of restarting it. A finished play-once video stays on its last frame until the next screen-off/on cycle.
- **Pause when hidden** — playback pauses whenever the wallpaper is not visible.
- **Battery-saver aware** — playback pauses while Android's battery saver is enabled and resumes when it is disabled.
- **Audio focus** — optional sound uses proper audio-focus handling, so it pauses or ducks for calls, music, and other media apps.

### App

- **Three languages** — English, Tiếng Việt, and 简体中文.
- **Hide from launcher** — optionally hide the launcher icon after setup.
- **Small footprint** — R8 minification and resource shrinking.
- **Private by design** — no ads, no analytics, no tracking, and no network access except for remote streams you choose yourself.

## Requirements

- **Android 7.0 (API 24) or newer.**
- A launcher/system that supports live wallpapers.
- A video the device can decode (H.264/AVC is the safest choice; H.265/HEVC, VP9, and others depend on the device).
- AOSP-based Android works best. Some heavily customized OEM ROMs aggressively stop or restrict wallpaper services.

![Preview](preview.gif)

## Usage

1. Open **VideoLiveWallpaper**.
2. Choose a source:
   - **Choose Video File** — pick a video with the system picker. It is copied into app-private storage; large videos can take a moment (a “Preparing video…” toast is shown).
   - **Add Video File Path** — enter a local path, a `file://`/`content://` URI, or a remote stream URL.
3. The live-wallpaper preview opens automatically. Confirm to apply the wallpaper.
4. Open **Settings** to configure looping, sound, and launcher icon visibility.

If the wallpaper is already applied, changing the video reloads the running service automatically.

### Supported sources

| Source | Example | Notes |
| --- | --- | --- |
| System picker | Any video shown in the document picker | Copied into app-private storage |
| Absolute path | `/storage/emulated/0/Movies/clip.mp4` | Requires the media permission on Android 13+ |
| File URI | `file:///storage/emulated/0/Movies/clip.mp4` | Copied into app-private storage |
| Content URI | `content://com.android.providers.media.documents/...` | Copied if it can be opened |
| HTTP/HTTPS stream | `https://example.com/video.mp4` | Progressive streaming, played directly by MediaPlayer |
| RTSP stream | `rtsp://example.com/live` | Depends on the device's MediaPlayer support |

Unsupported schemes are rejected with a toast. Cleartext HTTP is allowed for remote streams, but HTTPS is recommended. Remote playback is delegated to Android's `MediaPlayer`, which supports progressive HTTP/HTTPS and RTSP — not HLS (`.m3u8`), DASH (`.mpd`), or RTMP.

## Settings

| Setting | Default | Description |
| --- | --- | --- |
| **Loop video** | On | On: play continuously. Off: play once each time the screen turns on. |
| **Play video with sound** | Off | Unmutes the video and requests audio focus while the wallpaper is visible. |
| **Hide icon from launcher** | Off | Hides the launcher icon. To reopen the app, use Android Settings → Apps → VideoLiveWallpaper → Open, or the live-wallpaper settings entry. |

## Permissions

| Permission | When it is used |
| --- | --- |
| `SET_WALLPAPER` | Applying the live wallpaper. |
| `INTERNET` | Playing remote `http`/`https`/`rtsp` streams. |
| `READ_EXTERNAL_STORAGE` | Manual file paths on Android 7.0–12L (`maxSdkVersion="32"`). |
| `READ_MEDIA_VIDEO` | Manual file paths on Android 13+, requested only when **Add Video File Path** is tapped. |

The system video picker does **not** require a storage permission: the selected URI is read once and copied into app-private storage. The wallpaper service is protected by the system-enforced `BIND_WALLPAPER` permission.

## How it works

- **Two processes.** The UI runs in the normal app process; the wallpaper engine runs in the `:wallpaper` process declared in the manifest. Both share the same UID, so app-private files are accessible to both.
- **Video import.** `VideoSourceStore` copies the selected stream into `noBackupFilesDir/wallpaper_video_<timestamp>`, writes the path to `filesDir/video_live_wallpaper_file_path`, and deletes the previous cached video. Unique file names guarantee the service sees a source change and reloads. Remote URLs are stored unchanged.
- **Cross-process settings.** Loop and sound flags are stored as small marker files (`play_once`, `unmute`) in `filesDir`, because `SharedPreferences` are not reliably synchronized across processes.
- **Reload signal.** The UI sends a package-scoped `moe.cyunrei.videolivewallpaper.SETTINGS_CHANGED` broadcast. The running engine re-reads the source and either applies the new settings or recreates the player.
- **Playback lifecycle.** A `MediaPlayer` renders into the wallpaper `SurfaceHolder`. The engine pauses when invisible, releases the player when the surface is destroyed, saves/restores the playback position across surface recreation, rewinds on `ACTION_SCREEN_OFF`, pauses under battery saver, and manages audio focus when sound is enabled.
- **Play-once semantics.** Play-once is tied to the screen-off/on cycle: screen-off rewinds, screen-on plays one pass. Switching apps does not reset the video.

## Build and CI

- **Gradle:** 9.6.0 (wrapper)
- **Android Gradle Plugin:** 9.4.1
- **JDK:** 17
- **compileSdk / targetSdk:** 37
- **minSdk:** 24 (Android 7.0)
- **Version:** 0.1.4.6-nano

The workflow runs `./gradlew clean assembleDebug assembleRelease` on every push to and on manual dispatch, then uploads both apks as artifacts.

## Build locally

Prerequisites: JDK 17 and Android SDK platform 37. The Gradle wrapper is included.

```sh
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk
```

Release builds enable minification and resource shrinking.

## Changelog

### 0.1.4.6-nano

- Android 17+ support.
- Reliable video loading (picked videos are copied into app-private storage)
- Play-once mode.

### Earlier

- Android 10+ support.
- Sound on/off option.
- Hide launcher icon option.
- Fixes for repeated wallpaper application, crashes after video selection, and black screens after applying the wallpaper.

## Contributing

Issues and pull requests are welcome. Keep changes focused, preserve the existing code style, and test playback on a real device when possible. Translation improvements are also appreciated.

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Credits

Originally created by [Cyunrei](https://github.com/Cyunrei). Maintained at [console-ramoops/Video-Live-Wallpaper](https://github.com/console-ramoops/Video-Live-Wallpaper). Thanks to all contributors and translators.
