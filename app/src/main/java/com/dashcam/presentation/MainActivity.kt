package com.dashcam.presentation

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.repository.PinSession
import com.dashcam.domain.repository.RecorderController
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
    private val recorder: RecorderController,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onAppBackgrounded() = pinSession.lock()

    /** Volume up / camera key from a Bluetooth shutter remote; true when it was used. */
    fun onRemoteKey(event: KeyEvent): Boolean {
        if (settings.value?.remoteButtonMarksEvent != true || recorder.state.value !is RecorderState.Recording) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) recorder.markEvent()
        return true
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        containerViewModelFactory { MainViewModel(it.settingsRepository, it.pinSession, it.recorderController) }
    }
    private var startRecordingRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in REMOTE_KEYS && viewModel.onRemoteKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_START_RECORDING, false)) startRecordingRequest = true
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    companion object {
        const val EXTRA_START_RECORDING = "com.dashcam.extra.START_RECORDING"
        /** What Bluetooth "camera shutter" remotes send. */
        private val REMOTE_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CAMERA)
    }
}
