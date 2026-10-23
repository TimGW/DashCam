package com.dashcam.domain.repository

import com.dashcam.domain.model.AppSettings
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
    suspend fun createPhotoFile(): String
    /** Returns the new id (path) of the recording. */
    suspend fun setLocked(id: String, locked: Boolean): String
    suspend fun delete(ids: Collection<String>)
    suspend fun storageStats(): StorageStats
    /** The file currently being written; hidden from listings. */
    fun setInProgress(id: String?)
    fun refresh()
}

interface LocationRepository {
    val location: StateFlow<LocationSample?>
}

interface ImpactDetector {
    /** Emits the measured g-force each time an impact above [thresholdG] is detected. */
    fun impacts(thresholdG: Float): Flow<Float>
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
    fun setAudioMuted(muted: Boolean)
    suspend fun takePhoto()
}

/** In-memory unlock state of the PIN-protected recordings screen. */
interface PinSession {
    val isUnlocked: StateFlow<Boolean>
    fun unlock()
    fun lock()
}
