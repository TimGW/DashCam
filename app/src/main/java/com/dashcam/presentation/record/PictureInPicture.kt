package com.dashcam.presentation.record

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import com.dashcam.R
import com.dashcam.service.RecorderService

/** Whether the activity is currently shown as a picture-in-picture window. */
@Composable
fun rememberIsInPictureInPictureMode(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity ?: return false
    var inPip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
            inPip = it.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * Lets the activity shrink into a picture-in-picture window when the user leaves the app while
 * [enabled], like a video player. The window gets [aspectRatio] and lock / stop actions.
 */
@Composable
fun PictureInPictureEffect(enabled: Boolean, aspectRatio: Rational, isLocked: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val currentEnabled by rememberUpdatedState(enabled)
    DisposableEffect(activity, enabled, aspectRatio, isLocked) {
        val params = pictureInPictureParams(activity, enabled, aspectRatio, isLocked)
        activity.setPictureInPictureParams(params)
        // Before Android 12 there's no auto-enter, so enter when the user navigates away.
        val onUserLeaveHint = Runnable {
            if (currentEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                activity.enterPictureInPictureMode(params)
            }
        }
        activity.addOnUserLeaveHintListener(onUserLeaveHint)
        onDispose {
            activity.removeOnUserLeaveHintListener(onUserLeaveHint)
            activity.setPictureInPictureParams(pictureInPictureParams(activity, false, aspectRatio, isLocked))
        }
    }
}

private fun pictureInPictureParams(
    context: Context,
    enabled: Boolean,
    aspectRatio: Rational,
    isLocked: Boolean,
): PictureInPictureParams {
    val lock = RemoteAction(
        Icon.createWithResource(context, if (isLocked) R.drawable.ic_lock_open else R.drawable.ic_lock),
        context.getString(if (isLocked) R.string.action_unlock else R.string.action_lock),
        context.getString(if (isLocked) R.string.cd_unlock_clip else R.string.cd_lock_clip),
        RecorderService.lockPendingIntent(context),
    )
    val stop = RemoteAction(
        Icon.createWithResource(context, R.drawable.ic_stop),
        context.getString(R.string.action_stop),
        context.getString(R.string.cd_stop_recording),
        RecorderService.stopPendingIntent(context),
    )
    return PictureInPictureParams.Builder()
        .setAspectRatio(aspectRatio)
        .setActions(listOf(lock, stop))
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(enabled) }
        .build()
}
