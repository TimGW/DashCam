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
             (foreground recorder, navigation
              listener, Quick Settings tile)
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
RecordScreen / tile / navigation
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

## Starting while navigating

[`NavigationListenerService`](../app/src/main/java/com/dashcam/service/NavigationListenerService.kt)
is a notification listener. It treats a `CATEGORY_NAVIGATION` notification (Google Maps and
others), or Waze's ongoing notification, as active navigation and reports it to
[`NavigationAutoRecorder`](../app/src/main/java/com/dashcam/data/recorder/NavigationAutoRecorder.kt).

Android doesn't let a background app open the camera. So with "Display over other apps" granted,
DashCam brings itself to the front, starts recording, and then moves to picture-in-picture or the
background. Without that permission it posts a notification to tap. Only recordings that
navigation started are stopped when navigation ends, after the configured delay.

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
