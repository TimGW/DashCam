package com.dashcam.data.recordings

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.domain.repository.RecordingExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a recording into the shared gallery (Movies/DashCam or Pictures/DashCam). */
class MediaStoreExporter(private val context: Context) : RecordingExporter {

    override suspend fun saveToGallery(recording: Recording): Boolean = withContext(Dispatchers.IO) {
        val file = File(recording.id)
        val isVideo = recording.type == RecordingType.VIDEO
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/DashCam" else "Pictures/DashCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return@withContext false
        runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("No output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure { resolver.delete(uri, null, null) }.isSuccess
    }
}
