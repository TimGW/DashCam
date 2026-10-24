package com.dashcam.data.recorder

import android.content.Context
import androidx.core.content.ContextCompat
import com.dashcam.domain.model.RecorderEvent
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.repository.PinSession
import com.dashcam.domain.repository.RecorderController
import com.dashcam.service.RecorderService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Starts recording through the foreground [RecorderService] so it survives leaving the app. */
class ServiceRecorderController(
    private val context: Context,
    private val session: RecordingSession,
) : RecorderController {

    override val state: StateFlow<RecorderState> = session.state
    override val events: Flow<RecorderEvent> = session.events

    override fun start() {
        ContextCompat.startForegroundService(context, RecorderService.startIntent(context))
    }

    override fun stop() = session.end()

    override fun toggleLock() = session.toggleLock()

    override fun markEvent() = session.markEvent()

    override fun setAudioMuted(muted: Boolean) = session.setAudioMuted(muted)

    override suspend fun takePhoto() = session.takePhoto()
}

class InMemoryPinSession : PinSession {
    private val unlocked = MutableStateFlow(false)
    override val isUnlocked: StateFlow<Boolean> = unlocked.asStateFlow()
    override fun unlock() {
        unlocked.value = true
    }

    override fun lock() {
        unlocked.value = false
    }
}
