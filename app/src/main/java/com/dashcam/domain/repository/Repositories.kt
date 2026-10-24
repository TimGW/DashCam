package com.dashcam.domain.repository

import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.DeviceHealth
import com.dashcam.domain.model.CameraState
import com.dashcam.domain.model.LocationSample
import com.dashcam.domain.model.RecorderEvent
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.StorageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface RecordingsRepository {
    fun observeRecordings(): Flow<List<Recording>>
    suspend fun getRecordings(): List<Recording>
    suspend fun createVideoFile(): String
    /** Path for an event clip around [eventAtMillis]; event clips are locked from the start. */
    suspend fun createEventFile(eventAtMillis: Long): String
    /** Path for the front camera's video recorded alongside [videoPath]. */
    fun secondaryVideoFile(videoPath: String): String
    suspend fun createPhotoFile(): String
    /** Returns the new id (path) of the recording. */
    suspend fun setLocked(id: String, locked: Boolean): String
    suspend fun delete(ids: Collection<String>)
    suspend fun storageStats(): StorageStats
    /** Files being written are hidden from listings, so they're never shown or deleted. */
    fun markInProgress(ids: Collection<String>)
    fun clearInProgress(ids: Collection<String>)
    fun refresh()
}

interface LocationRepository {
    val location: StateFlow<LocationSample?>
}

interface ImpactDetector {
    /** Emits the measured g-force each time an impact above [thresholdG] is detected. */
    fun impacts(thresholdG: Float): Flow<Float>
}

interface DeviceHealthMonitor {
    /** Current temperature and battery state, updated as they change. */
    val health: Flow<DeviceHealth>
}

interface RecordingExporter {
    suspend fun saveToGallery(recording: Recording): Boolean
}

interface CameraController {
    val state: StateFlow<CameraState>
    fun setTorch(enabled: Boolean)
    fun scaleZoom(factor: Float)
}

interface RecorderController {
    val state: StateFlow<RecorderState>
    val events: Flow<RecorderEvent>
    fun start()
    fun stop()
    fun toggleLock()
    /** Marks this moment as an event: locks the clip and saves an event clip around it. */
    fun markEvent()
    fun setAudioMuted(muted: Boolean)
    suspend fun takePhoto()
}

/** In-memory unlock state of the PIN-protected recordings screen. */
interface PinSession {
    val isUnlocked: StateFlow<Boolean>
    fun unlock()
    fun lock()
}
