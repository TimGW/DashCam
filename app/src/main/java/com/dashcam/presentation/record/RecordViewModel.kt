package com.dashcam.presentation.record

import androidx.camera.core.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dashcam.data.camera.CameraPreviewHost
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.CameraFacing
import com.dashcam.domain.model.CameraState
import com.dashcam.domain.model.LocationSample
import com.dashcam.domain.model.RecorderEvent
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.repository.CameraController
import com.dashcam.domain.repository.LocationRepository
import com.dashcam.domain.repository.RecorderController
import com.dashcam.domain.repository.RecordingsRepository
import com.dashcam.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordUiState(
    val recorder: RecorderState = RecorderState.Idle,
    val camera: CameraState = CameraState(),
    val location: LocationSample? = null,
    val settings: AppSettings? = null,
    val freeBytes: Long? = null,
) {
    val isRecording: Boolean get() = recorder is RecorderState.Recording
}

class RecordViewModel(
    private val recorder: RecorderController,
    private val camera: CameraController,
    private val preview: CameraPreviewHost,
    private val settingsRepository: SettingsRepository,
    locationRepository: LocationRepository,
    private val recordingsRepository: RecordingsRepository,
) : ViewModel() {

    private val cameraPermission = MutableStateFlow(false)
    private val localEvents = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 4)
    private var autoStartHandled = false

    private val freeBytes = flow {
        while (true) {
            emit(recordingsRepository.storageStats().freeBytes)
            delay(15_000)
        }
    }

    val uiState: StateFlow<RecordUiState> = combine(
        recorder.state,
        camera.state,
        locationRepository.location,
        settingsRepository.settings,
        freeBytes,
    ) { recorderState, cameraState, location, settings, free ->
        RecordUiState(recorderState, cameraState, location, settings, free)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    val events: Flow<RecorderEvent> = merge(recorder.events, localEvents)

    init {
        viewModelScope.launch {
            combine(cameraPermission, settingsRepository.settings) { granted, settings ->
                if (granted) settings.cameraFacing to settings.videoQuality else null
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (facing, quality) -> preview.configure(facing, quality) }
        }
    }

    fun onCameraPermission(granted: Boolean) {
        cameraPermission.value = granted
    }

    /** Starts recording when requested externally (tile) or when "start on launch" is enabled. */
    fun maybeAutoStart(requested: Boolean) {
        viewModelScope.launch {
            if (!cameraPermission.value) return@launch
            val settings = settingsRepository.settings.first()
            val shouldStart = requested || (settings.autoStartOnLaunch && !autoStartHandled)
            autoStartHandled = true
            if (shouldStart && recorder.state.value is RecorderState.Idle) recorder.start()
        }
    }

    fun toggleRecording() {
        if (recorder.state.value is RecorderState.Recording) recorder.stop() else recorder.start()
    }

    fun toggleLock() = recorder.toggleLock()

    fun takePhoto() {
        viewModelScope.launch { recorder.takePhoto() }
    }

    fun toggleAudio() {
        val state = recorder.state.value
        if (state is RecorderState.Recording) {
            recorder.setAudioMuted(!state.isAudioMuted)
        } else {
            viewModelScope.launch { settingsRepository.update { it.copy(recordAudio = !it.recordAudio) } }
        }
    }

    fun toggleTorch() = camera.setTorch(!camera.state.value.torchOn)

    fun switchCamera() {
        if (recorder.state.value is RecorderState.Recording) {
            localEvents.tryEmit(RecorderEvent.StopToSwitchCamera)
            return
        }
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(cameraFacing = if (it.cameraFacing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK)
            }
        }
    }

    fun zoom(factor: Float) = camera.scaleZoom(factor)

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) = preview.attachPreview(surfaceProvider)

    fun setPreviewVisible(visible: Boolean) = preview.setPreviewVisible(visible)

    fun setTargetRotation(rotation: Int) = preview.setTargetRotation(rotation)
}
