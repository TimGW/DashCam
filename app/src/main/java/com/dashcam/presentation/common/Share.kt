package com.dashcam.presentation.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import java.io.File

fun Context.shareRecording(recording: Recording) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", File(recording.id))
    val send = Intent(Intent.ACTION_SEND)
        .setType(if (recording.type == RecordingType.VIDEO) "video/mp4" else "image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}
