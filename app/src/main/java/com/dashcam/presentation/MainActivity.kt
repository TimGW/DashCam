package com.dashcam.presentation

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
    private var minimizeAfterStart by mutableStateOf(false)
    private var returnToPackage: String? = null

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
                    minimizeAfterStart = minimizeAfterStart,
                    onMinimized = ::onMinimized,
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
        if (intent.getBooleanExtra(EXTRA_MINIMIZE_AFTER_START, false)) {
            minimizeAfterStart = true
            returnToPackage = intent.getStringExtra(EXTRA_RETURN_TO_PACKAGE)
        }
    }

    private var returnWhenInPictureInPicture = false

    /**
     * Recording started for another app: bring that app back (its own PiP was replaced by ours).
     * When entering PiP, wait until it has finished, or the transition sends that app back again.
     */
    private fun onMinimized(enteringPictureInPicture: Boolean) {
        minimizeAfterStart = false
        if (enteringPictureInPicture) returnWhenInPictureInPicture = true else returnToApp()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode && returnWhenInPictureInPicture) returnToApp()
    }

    private fun returnToApp() {
        returnWhenInPictureInPicture = false
        val launch = returnToPackage?.let(packageManager::getLaunchIntentForPackage) ?: return
        returnToPackage = null
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    companion object {
        const val EXTRA_START_RECORDING = "com.dashcam.extra.START_RECORDING"
        private const val EXTRA_MINIMIZE_AFTER_START = "com.dashcam.extra.MINIMIZE_AFTER_START"
        /** What Bluetooth "camera shutter" remotes send. */
        private val REMOTE_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CAMERA)
        private const val EXTRA_RETURN_TO_PACKAGE = "com.dashcam.extra.RETURN_TO_PACKAGE"

        /**
         * Starts recording, then gets out of the way (PiP or background) and brings [returnTo],
         * the app the user was in, back to the front.
         */
        fun autoRecordIntent(context: Context, returnTo: String?): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_START_RECORDING, true)
            .putExtra(EXTRA_MINIMIZE_AFTER_START, true)
            .putExtra(EXTRA_RETURN_TO_PACKAGE, returnTo)
    }
}
