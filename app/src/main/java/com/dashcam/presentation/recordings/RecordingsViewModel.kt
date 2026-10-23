package com.dashcam.presentation.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.domain.model.SortOrder
import com.dashcam.domain.model.StorageStats
import com.dashcam.domain.repository.PinSession
import com.dashcam.domain.repository.RecordingExporter
import com.dashcam.domain.repository.RecordingsRepository
import com.dashcam.domain.repository.SettingsRepository
import com.dashcam.domain.usecase.DeleteRecordingsUseCase
import com.dashcam.domain.usecase.ManagePinUseCase
import com.dashcam.domain.usecase.ObserveRecordingsUseCase
import com.dashcam.domain.usecase.SetRecordingsLockedUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordingsUiState(
    val pinRequired: Boolean = false,
    val type: RecordingType = RecordingType.VIDEO,
    val sort: SortOrder = SortOrder.NEWEST,
    val items: List<Recording> = emptyList(),
    val isLoading: Boolean = true,
    val selection: Set<String> = emptySet(),
    val stats: StorageStats? = null,
    val maxStorageGb: Int = 0,
)

sealed interface RecordingsMessage {
    data object SavedToGallery : RecordingsMessage
    data object SaveFailed : RecordingsMessage
    data class Deleted(val count: Int) : RecordingsMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModel(
    observeRecordings: ObserveRecordingsUseCase,
    private val setRecordingsLocked: SetRecordingsLockedUseCase,
    private val deleteRecordings: DeleteRecordingsUseCase,
    private val exporter: RecordingExporter,
    private val managePin: ManagePinUseCase,
    pinSession: PinSession,
    settingsRepository: SettingsRepository,
    private val recordingsRepository: RecordingsRepository,
) : ViewModel() {

    private val type = MutableStateFlow(RecordingType.VIDEO)
    private val sort = MutableStateFlow(SortOrder.NEWEST)
    private val selection = MutableStateFlow(emptySet<String>())
    private val _messages = MutableSharedFlow<RecordingsMessage>(extraBufferCapacity = 4)
    val messages: Flow<RecordingsMessage> = _messages.asSharedFlow()

    private val items = combine(type, sort, ::Pair).flatMapLatest { (t, s) -> observeRecordings(t, s) }
    private val stats = recordingsRepository.observeRecordings().map { recordingsRepository.storageStats() }
    private val access = combine(settingsRepository.settings, pinSession.isUnlocked) { settings, unlocked ->
        (settings.isPinEnabled && !unlocked) to settings.maxStorageGb
    }

    val uiState: StateFlow<RecordingsUiState> = combine(
        items, combine(type, sort, ::Pair), selection, stats, access,
    ) { list, (t, s), selected, storage, (pinRequired, maxGb) ->
        RecordingsUiState(
            pinRequired = pinRequired,
            type = t,
            sort = s,
            items = list,
            isLoading = false,
            selection = selected.filterTo(mutableSetOf()) { id -> list.any { it.id == id } },
            stats = storage,
            maxStorageGb = maxGb,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState())

    init {
        recordingsRepository.refresh()
    }

    suspend fun unlock(pin: String): Boolean = managePin.verify(pin)

    fun selectType(value: RecordingType) {
        type.value = value
        selection.value = emptySet()
    }

    fun selectSort(value: SortOrder) {
        sort.value = value
    }

    fun toggleSelection(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun setLocked(ids: Collection<String>, locked: Boolean) {
        viewModelScope.launch {
            setRecordingsLocked(ids, locked)
            clearSelection()
        }
    }

    fun delete(ids: Collection<String>) {
        viewModelScope.launch {
            deleteRecordings(ids)
            clearSelection()
            _messages.tryEmit(RecordingsMessage.Deleted(ids.size))
        }
    }

    fun saveToGallery(recording: Recording) {
        viewModelScope.launch {
            val saved = exporter.saveToGallery(recording)
            _messages.tryEmit(if (saved) RecordingsMessage.SavedToGallery else RecordingsMessage.SaveFailed)
        }
    }
}
