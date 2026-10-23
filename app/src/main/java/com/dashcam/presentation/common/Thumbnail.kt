package com.dashcam.presentation.common

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val thumbnailCache = LruCache<String, Bitmap>(80)

@Composable
fun RecordingThumbnail(recording: Recording, modifier: Modifier = Modifier) {
    val key = "${recording.id}:${recording.sizeBytes}"
    val bitmap by produceState(thumbnailCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadThumbnail(recording, Size(320, 240)) }
                ?.also { thumbnailCache.put(key, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (recording.type == RecordingType.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun loadThumbnail(recording: Recording, size: Size): Bitmap? = runCatching {
    val file = File(recording.id)
    when (recording.type) {
        RecordingType.VIDEO -> ThumbnailUtils.createVideoThumbnail(file, size, null)
        RecordingType.PHOTO -> ThumbnailUtils.createImageThumbnail(file, size, null)
    }
}.getOrNull()
