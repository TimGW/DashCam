# Architecture

DashCam is a single-module app split into three layers, wired together by hand in
[`AppContainer`](../app/src/main/java/com/dashcam/di/AppContainer.kt) (one instance per process,
no DI framework).

```
presentation  ──▶  domain  ◀──  data
 (Compose,          (models,       (CameraX, sensors,
  view models)       interfaces,    files, DataStore)
                     use cases)
        ▲                              ▲
        └──────────  service  ─────────┘
             (foreground recorder,
              Quick Settings tile)
```

- **domain** holds plain Kotlin: settings and recording models, repository interfaces
  (`Repositories.kt`) and use cases. It has no Android dependencies, so it's covered by JVM
  unit tests.
- **data** implements those interfaces against Android: CameraX, the accelerometer, location,
  the file system, MediaStore and DataStore.
- **presentation** is Jetpack Compose with one view model per screen, created through
  `ViewModelFactory`.
- **service** contains the Android components that must outlive the UI.

## Recording

```
RecordScreen / tile / notification
        │  RecorderController
        ▼
ServiceRecorderController ──starts──▶ RecorderService (foreground: camera|microphone|location)
        │
        ▼
RecordingSession ──▶ CameraEngine (CameraX) ──▶ VideoOverlay, RoundedInsetEffect
        ├──▶ EnforceStorageLimitUseCase   frees space before every clip
        ├──▶ ImpactDetector               locks the clip on impact
        ├──▶ DeviceHealthMonitor          ──▶ SessionHealthPolicy
        ├──▶ TelemetryWriter              .srt / .gpx sidecars
        ├──▶ EventClipExporter            event clips
        └──▶ EventLog
```

[`RecordingSession`](../app/src/main/java/com/dashcam/data/recorder/RecordingSession.kt) is the
loop-recording engine. It records consecutive clips of the configured length and runs on the main
thread. Some details:

- **Clip handover.** The next clip is queued before the current file is finalized, so CameraX
  starts it as soon as it can and almost no footage is lost between clips.
- **Storage.** Before each clip, `EnforceStorageLimitUseCase` deletes the oldest unlocked videos
  until the recordings plus the coming clip fit the limit. Clip sizes are estimated from
  `VideoEncoding`.
- **Failures.** If the camera fails or a clip stalls, the session keeps going and retries until
  the camera is back. If Android kills the process, `RecorderService` posts a notification on
  restart so recording can be resumed with one tap.
- **Device health.** `SessionHealthPolicy` turns temperature and battery readings into a verdict:
  cap the quality at 720p, warn about the battery, or stop and save the clip before the phone
  shuts the camera down.
- **Locking and events.** An impact or a marked event locks the current clip (`_locked` suffix).
  When event clips are on, `EventClipExporter` remuxes the seconds around the event from the
  finished clips into a separate `_event` file, starting at the key frame just before.
- **Overlay.** `VideoOverlay` is a CameraX effect that draws the date, speed, coordinates and
  label into the encoded video. Where the overlay can't be applied (such as the separate
  front-camera file), `TelemetryWriter` writes the same data as `.srt` subtitles instead.

## Controls while driving

`RecorderService` posts its notification as an Android 16 Live Update: a status bar chip with
the elapsed time (or "Locked"), pinned at the top of the shade and on the lock screen, with Lock
and Stop actions. On older Android, or with Live Updates turned off, it's a regular notification
on a high-importance (but silent) channel, which still ranks it near the top.

With "Lock by voice" on, the service runs
[`VoiceCommandListener`](../app/src/main/java/com/dashcam/data/recorder/VoiceCommandListener.kt)
for the length of the session. It restarts Android's `SpeechRecognizer` (on-device where
available) after every utterance, and locks the current clip when a short utterance contains
"lock", "slot" or "vergrendel".

## Storage and settings

- [`FileRecordingsRepository`](../app/src/main/java/com/dashcam/data/recordings/FileRecordingsRepository.kt)
  scans app-specific external storage. It has no database: lock state, front camera and event
  clips are encoded in file name suffixes (see the README), so they survive reinstalls and are
  visible in a file manager.
- [`MediaStoreExporter`](../app/src/main/java/com/dashcam/data/recordings/MediaStoreExporter.kt)
  copies a recording into the shared gallery.
- [`DataStoreSettingsRepository`](../app/src/main/java/com/dashcam/data/settings/DataStoreSettingsRepository.kt)
  persists [`AppSettings`](../app/src/main/java/com/dashcam/domain/model/AppSettings.kt), which
  also documents every option's default.
- The PIN is stored as a hash in settings. Unlocking only lasts for the process (`InMemoryPinSession`).

## Tests

JVM unit tests live in `app/src/test` and cover the domain logic and pure helpers: storage limit
enforcement, the health policy, bitrate and clip size estimates, file naming and the event log.

```sh
./gradlew testDebugUnitTest
```
