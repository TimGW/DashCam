package com.dashcam.di

import android.app.Application
import com.dashcam.data.camera.CameraEngine
import com.dashcam.data.device.AndroidDeviceHealthMonitor
import com.dashcam.data.log.EventLog
import com.dashcam.data.location.AndroidLocationRepository
import com.dashcam.data.recorder.HapticFeedback
import com.dashcam.data.recorder.InMemoryPinSession
import com.dashcam.data.recorder.NavigationAutoRecorder
import com.dashcam.data.recorder.RecordingSession
import com.dashcam.data.recorder.ServiceRecorderController
import com.dashcam.data.recordings.FileRecordingsRepository
import com.dashcam.data.recordings.MediaStoreExporter
import com.dashcam.data.sensor.AccelerometerImpactDetector
import com.dashcam.data.settings.DataStoreSettingsRepository
import com.dashcam.domain.repository.CameraController
import com.dashcam.domain.repository.ImpactDetector
import com.dashcam.domain.repository.LocationRepository
import com.dashcam.domain.repository.PinSession
import com.dashcam.domain.repository.RecorderController
import com.dashcam.domain.repository.RecordingExporter
import com.dashcam.domain.repository.RecordingsRepository
import com.dashcam.domain.repository.SettingsRepository
import com.dashcam.domain.usecase.DeleteRecordingsUseCase
import com.dashcam.domain.usecase.EnforceStorageLimitUseCase
import com.dashcam.domain.usecase.ManagePinUseCase
import com.dashcam.domain.usecase.ObserveRecordingsUseCase
import com.dashcam.domain.usecase.SetRecordingsLockedUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency graph; one instance per process. */
class AppContainer(app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(app)
    val recordingsRepository: RecordingsRepository = FileRecordingsRepository(app)
    val locationRepository: LocationRepository = AndroidLocationRepository(app, appScope)
    val recordingExporter: RecordingExporter = MediaStoreExporter(app)
    val pinSession: PinSession = InMemoryPinSession()
    private val impactDetector: ImpactDetector = AccelerometerImpactDetector(app)
    val eventLog = EventLog(File(app.filesDir, "event_log.txt"))

    val cameraEngine = CameraEngine(app)
    val cameraController: CameraController get() = cameraEngine

    val recordingSession = RecordingSession(
        camera = cameraEngine,
        recordings = recordingsRepository,
        settingsRepository = settingsRepository,
        location = locationRepository,
        impactDetector = impactDetector,
        enforceStorageLimit = EnforceStorageLimitUseCase(recordingsRepository, settingsRepository),
        deviceHealth = AndroidDeviceHealthMonitor(app),
        eventLog = eventLog,
        haptics = HapticFeedback(app),
        scope = appScope,
    )
    val recorderController: RecorderController = ServiceRecorderController(app, recordingSession)
    val navigationAutoRecorder =
        NavigationAutoRecorder(app, settingsRepository, recorderController, eventLog, appScope)

    val observeRecordings = ObserveRecordingsUseCase(recordingsRepository)
    val setRecordingsLocked = SetRecordingsLockedUseCase(recordingsRepository)
    val deleteRecordings = DeleteRecordingsUseCase(recordingsRepository)
    val managePin = ManagePinUseCase(settingsRepository, pinSession)
}
