package com.dashcam.domain.usecase

import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.domain.model.SortOrder
import com.dashcam.domain.repository.RecordingsRepository
import com.dashcam.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ObserveRecordingsUseCase(private val repository: RecordingsRepository) {
    operator fun invoke(type: RecordingType, sort: SortOrder): Flow<List<Recording>> =
        repository.observeRecordings().map { all ->
            val ofType = all.filter { it.type == type }
            when (sort) {
                SortOrder.NEWEST -> ofType.sortedByDescending { it.createdAtMillis }
                SortOrder.OLDEST -> ofType.sortedBy { it.createdAtMillis }
                SortOrder.NAME -> ofType.sortedBy { it.name }
                SortOrder.LARGEST -> ofType.sortedByDescending { it.sizeBytes }
                SortOrder.LONGEST -> ofType.sortedByDescending { it.durationMillis ?: 0L }
            }
        }
}

class SetRecordingsLockedUseCase(private val repository: RecordingsRepository) {
    suspend operator fun invoke(ids: Collection<String>, locked: Boolean) {
        ids.forEach { repository.setLocked(it, locked) }
        repository.refresh()
    }
}

class DeleteRecordingsUseCase(private val repository: RecordingsRepository) {
    suspend operator fun invoke(ids: Collection<String>) {
        repository.delete(ids)
        repository.refresh()
    }
}

/**
 * Loop recording: frees space by deleting the oldest unlocked videos until the recordings fit the
 * configured limit and the device keeps a minimum of free space.
 */
class EnforceStorageLimitUseCase(
    private val recordings: RecordingsRepository,
    private val settings: SettingsRepository,
) {
    enum class Result { OK, FULL }

    suspend operator fun invoke(): Result {
        val current = settings.settings.first()
        val limit = if (current.maxStorageGb == 0) Long.MAX_VALUE else current.maxStorageGb * GB
        val stats = recordings.storageStats()
        var used = stats.recordingsBytes
        var free = stats.freeBytes
        if (used <= limit && free >= MIN_FREE_BYTES) return Result.OK
        if (!current.loopRecording) return Result.FULL

        val deletable = recordings.getRecordings()
            .filter { it.type == RecordingType.VIDEO && !it.isLocked }
            .sortedBy { it.createdAtMillis }
            .iterator()
        while (used > limit || free < MIN_FREE_BYTES) {
            if (!deletable.hasNext()) return Result.FULL
            val oldest = deletable.next()
            recordings.delete(listOf(oldest.id))
            used -= oldest.sizeBytes
            free += oldest.sizeBytes
        }
        recordings.refresh()
        return Result.OK
    }

    private companion object {
        const val GB = 1_000_000_000L
        const val MIN_FREE_BYTES = 300L * 1024L * 1024L
    }
}
