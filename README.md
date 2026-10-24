# DashCam

An Android dashcam app: it records the road in a loop, locks clips when it detects an impact,
and keeps going in the background or in picture-in-picture while you navigate.

Built with Kotlin, Jetpack Compose, CameraX and Media3.

> [!WARNING]
> **This project is entirely vibecoded.** All of the code, tests and documentation were written
> by an AI assistant from prompts. No human has reviewed the code line by line. Expect bugs,
> and don't rely on it as your only record of an accident or other important event. Review the
> code yourself before you build on it.

## Features

**Recording**
- Loop recording in clips of 1, 3, 5 or 10 minutes; the oldest unlocked clips are deleted when
  the storage limit (2–32 GB or unlimited) is reached
- Back camera, front camera, or both: the front camera as an inset, or as a separate video
- 480p to 4K, 30 or 60 fps, adjustable bitrate, 16:9 or 4:3 (full sensor)
- Wide angle (ultra-wide lens where available), infinity focus to ignore the windscreen,
  electronic stabilization
- Date, time, speed, GPS coordinates and a custom label (e.g. your number plate) burned into the video
- Optional `.gpx` track per clip; `.srt` subtitles with the same telemetry for videos that can't
  get the burned-in overlay
- Photos while recording

**Events**
- G-sensor impact detection with five sensitivity levels, locking the current clip
- Mark an event manually, or with a Bluetooth/headset media button or volume up
- Event clips: 30 or 60 seconds around the event, cut from the loop clips without re-encoding

**Hands-free**
- Start recording automatically when Google Maps navigates or Waze is open, and stop a set time
  after navigation ends
- Start on launch, Quick Settings tile, picture-in-picture viewfinder
- Foreground service that keeps recording with the screen off, and recovers when the camera is
  briefly unavailable

**Device health**
- Drops to 720p when the phone gets hot, and stops cleanly before it overheats
- Warns at 15 % battery and stops cleanly at 5 % (when not charging)

**Recordings**
- Browse, sort, play, lock, share or delete videos and photos
- Save to the gallery (`Movies/DashCam`, `Pictures/DashCam`)
- Optional PIN lock for the recordings screen
- Event log of starts, stops and camera problems

## Requirements

- Android 10 (API 29) or newer
- A camera; GPS and an accelerometer are optional

## Building

You need JDK 17+ and the Android SDK (compile SDK 36). Android Studio picks up the SDK
automatically; on the command line, set `sdk.dir` in `local.properties` or `ANDROID_HOME`.

```sh
./gradlew assembleDebug        # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected device
./gradlew testDebugUnitTest    # run the unit tests
```

## Permissions

| Permission | Used for | Required |
| --- | --- | --- |
| Camera | Recording | Yes |
| Microphone | Audio in recordings | No |
| Location | Speed, coordinates and GPX tracks | No |
| Notifications | Recording status notification | No |
| Notification access | Noticing when navigation starts | Only for "Record when navigating" |
| Display over other apps | Starting automatically when navigation starts, without a tap | No |

Excluding DashCam from battery optimization (Settings → Behavior) keeps long background
recordings from being stopped by Android.

## Where recordings are stored

Recordings live in the app's own storage, so no storage permission is needed:

```
Android/data/com.dashcam/files/Movies/    videos (+ .srt / .gpx sidecars)
Android/data/com.dashcam/files/Pictures/  photos
```

Files are named after the time recording started; the suffixes carry state, so nothing else
needs a database:

| File name | Meaning |
| --- | --- |
| `DC_20261024_101500.mp4` | Loop clip |
| `DC_20261024_101500_locked.mp4` | Locked clip, never deleted by loop recording |
| `DC_20261024_101500_front.mp4` | Front camera, recorded as a separate video |
| `DC_20261024_101500_event_locked.mp4` | Event clip around an impact or marked moment |
| `DC_20261024_101500_123.jpg` | Photo |

Uninstalling the app deletes this folder. Use **Save to gallery** to keep a copy.

## Project structure

```
app/src/main/java/com/dashcam/
├── domain/        models, repository interfaces and use cases (no Android dependencies)
├── data/          CameraX engine, recording session, sensors, location, storage, settings
├── service/       foreground recorder, navigation listener, Quick Settings tile
├── presentation/  Compose screens and view models
└── di/            AppContainer, the manual dependency graph
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit together.
