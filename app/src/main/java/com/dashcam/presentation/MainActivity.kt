package com.dashcam.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.repository.PinSession
import com.dashcam.domain.repository.SettingsRepository
import com.dashcam.presentation.common.containerViewModelFactory
import com.dashcam.presentation.navigation.DashCamNavHost
import com.dashcam.presentation.theme.DashCamTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(
    settingsRepository: SettingsRepository,
    private val pinSession: PinSession,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onAppBackgrounded() = pinSession.lock()
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        containerViewModelFactory { MainViewModel(it.settingsRepository, it.pinSession) }
    }
    private var startRecordingRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startRecordingRequest = intent.getBooleanExtra(EXTRA_START_RECORDING, false)
        }
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            DashCamTheme(themeMode = current.themeMode, dynamicColor = current.dynamicColor) {
                DashCamNavHost(
                    startRecordingRequest = startRecordingRequest,
                    onStartRecordingHandled = { startRecordingRequest = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_RECORDING, false)) startRecordingRequest = true
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    companion object {
        const val EXTRA_START_RECORDING = "com.dashcam.extra.START_RECORDING"
    }
}
