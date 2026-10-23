package com.dashcam.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.repository.SettingsRepository
import com.dashcam.domain.usecase.ManagePinUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val managePin: ManagePinUseCase,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    /** Applies a PIN change after verifying the current PIN (if one is set). */
    suspend fun changePin(currentPin: String?, newPin: String?): Boolean {
        if (currentPin != null && !managePin.verify(currentPin)) return false
        if (newPin == null) managePin.clearPin() else managePin.setPin(newPin)
        return true
    }
}
