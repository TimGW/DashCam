package com.dashcam.presentation.common

import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.domain.model.StorageStats

/** Sample data for Compose previews. The files don't exist, so thumbnails show their placeholder. */
object PreviewData {
    private const val START = 1_761_300_000_000L // 2025-10-24 10:00 UTC

    val video = Recording(
        id = "/preview/DC_20251024_100000.mp4",
        name = "DC_20251024_100000.mp4",
        type = RecordingType.VIDEO,
        createdAtMillis = START,
        sizeBytes = 450_000_000,
        durationMillis = 180_000,
        isLocked = false,
        subtitlesPath = null,
    )

    val lockedVideo = video.copy(
        id = "/preview/DC_20251024_100300_locked.mp4",
        name = "DC_20251024_100300_locked.mp4",
        createdAtMillis = START + 180_000,
        isLocked = true,
    )

    val eventVideo = video.copy(
        id = "/preview/DC_20251024_100412_event_locked.mp4",
        name = "DC_20251024_100412_event_locked.mp4",
        createdAtMillis = START + 252_000,
        sizeBytes = 75_000_000,
        durationMillis = 30_000,
        isLocked = true,
    )

    /** Both cameras, saved as separate videos from the same session. */
    val backCameraVideo = video.copy(
        id = "/preview/DC_20251024_100600.mp4",
        name = "DC_20251024_100600.mp4",
        createdAtMillis = START + 360_000,
        pairedWith = "/preview/DC_20251024_100600_front.mp4",
    )

    val frontCameraVideo = backCameraVideo.copy(
        id = "/preview/DC_20251024_100600_front.mp4",
        name = "DC_20251024_100600_front.mp4",
        sizeBytes = 225_000_000,
        pairedWith = backCameraVideo.id,
        isFrontCamera = true,
    )

    val photo = Recording(
        id = "/preview/DC_20251024_100125_421.jpg",
        name = "DC_20251024_100125_421.jpg",
        type = RecordingType.PHOTO,
        createdAtMillis = START + 85_000,
        sizeBytes = 3_200_000,
        durationMillis = null,
        isLocked = false,
        subtitlesPath = null,
    )

    val videos = listOf(backCameraVideo, frontCameraVideo, eventVideo, lockedVideo, video)

    val photos = List(6) { i -> photo.copy(id = "/preview/photo_$i.jpg", createdAtMillis = photo.createdAtMillis + i * 1000) }

    val storageStats = StorageStats(
        recordingsBytes = 5_400_000_000,
        freeBytes = 41_000_000_000,
        totalBytes = 128_000_000_000,
    )
}
