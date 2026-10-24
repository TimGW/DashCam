package com.dashcam.domain.model

enum class RecordingType { VIDEO, PHOTO }

/**
 * A saved video or photo. [id] is the absolute file path.
 *
 * When both cameras record to separate videos, each is its own recording: [pairedWith] is the id
 * of the other camera's video from the same session, and [isFrontCamera] tells the two apart.
 */
data class Recording(
    val id: String,
    val name: String,
    val type: RecordingType,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val isLocked: Boolean,
    val subtitlesPath: String?,
    val pairedWith: String? = null,
    val isFrontCamera: Boolean = false,
)

data class StorageStats(
    val recordingsBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

enum class SortOrder { NEWEST, OLDEST, NAME, LARGEST, LONGEST }
