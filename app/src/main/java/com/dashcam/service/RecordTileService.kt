package com.dashcam.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dashcam.DashCamApplication
import com.dashcam.R
import com.dashcam.domain.model.RecorderState
import com.dashcam.presentation.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: stops a running recording, or opens the app and starts recording. */
class RecordTileService : TileService() {

    private val controller get() = (application as DashCamApplication).container.recorderController
    private val scope = MainScope()
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening = scope.launch { controller.state.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (controller.state.value is RecorderState.Recording) {
            controller.stop()
            return
        }
        // Camera foreground services must be started from a visible activity.
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_START_RECORDING, true)
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun render(state: RecorderState) {
        val tile = qsTile ?: return
        val recording = state is RecorderState.Recording
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (recording) R.string.tile_stop else R.string.tile_record)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
