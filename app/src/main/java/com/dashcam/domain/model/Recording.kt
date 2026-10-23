package com.dashcam.domain.model

enum class RecordingType { VIDEO, PHOTO }

/** A saved video or photo. [id] is the absolute file path. */
data class Recording(
    val id: String,
    val name: String,
    val type: RecordingType,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val isLocked: Boolean,
    val subtitlesPath: String?,
)

data class StorageStats(
    val recordingsBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

enum class SortOrder { NEWEST, OLDEST, NAME, LARGEST, LONGEST }
