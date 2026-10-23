package com.dashcam.domain.usecase

import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.domain.model.StorageStats
import com.dashcam.domain.repository.RecordingsRepository
import com.dashcam.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EnforceStorageLimitUseCaseTest {

    private val gb = 1_000_000_000L

    @Test
    fun `nothing is deleted while everything fits`() = runTest {
        val repo = FakeRecordings(clips = listOf(clip("a", 1 * gb)), freeBytes = 50 * gb)
        val result = useCase(repo, maxStorageGb = 8)(reserveBytes = 1 * gb)
        assertEquals(EnforceStorageLimitUseCase.Result.Ok(deletedClips = 0), result)
    }

    @Test
    fun `the reserve for the next clip deletes the oldest unlocked clips first`() = runTest {
        val repo = FakeRecordings(
            clips = listOf(
                clip("oldest-locked", 2 * gb, createdAt = 1, locked = true),
                clip("old", 2 * gb, createdAt = 2),
                clip("new", 2 * gb, createdAt = 3),
            ),
            freeBytes = 50 * gb,
        )
        // 6 GB used + 3 GB reserve > 8 GB: one unlocked clip has to go, never the locked one.
        val result = useCase(repo, maxStorageGb = 8)(reserveBytes = 3 * gb)
        assertEquals(EnforceStorageLimitUseCase.Result.Ok(deletedClips = 1), result)
        assertEquals(listOf("old"), repo.deleted)
    }

    @Test
    fun `low free space on the device also frees clips`() = runTest {
        val repo = FakeRecordings(clips = listOf(clip("a", 1 * gb, createdAt = 1), clip("b", 1 * gb, createdAt = 2)), freeBytes = gb / 2)
        val result = useCase(repo, maxStorageGb = 0)(reserveBytes = gb / 4)
        assertEquals(EnforceStorageLimitUseCase.Result.Ok(deletedClips = 1), result)
        assertEquals(listOf("a"), repo.deleted)
    }

    @Test
    fun `full when only locked clips remain`() = runTest {
        val repo = FakeRecordings(clips = listOf(clip("locked", 8 * gb, locked = true)), freeBytes = 50 * gb)
        assertEquals(EnforceStorageLimitUseCase.Result.Full, useCase(repo, maxStorageGb = 8)(reserveBytes = gb))
    }

    @Test
    fun `full without loop recording instead of deleting`() = runTest {
        val repo = FakeRecordings(clips = listOf(clip("a", 8 * gb)), freeBytes = 50 * gb)
        val result = useCase(repo, maxStorageGb = 8, loop = false)(reserveBytes = gb)
        assertEquals(EnforceStorageLimitUseCase.Result.Full, result)
        assertEquals(emptyList<String>(), repo.deleted)
    }

    private fun useCase(repo: FakeRecordings, maxStorageGb: Int, loop: Boolean = true) =
        EnforceStorageLimitUseCase(repo, FakeSettings(AppSettings(maxStorageGb = maxStorageGb, loopRecording = loop)))

    private fun clip(id: String, size: Long, createdAt: Long = 0, locked: Boolean = false) = Recording(
        id = id, name = id, type = RecordingType.VIDEO, createdAtMillis = createdAt, sizeBytes = size,
        durationMillis = null, isLocked = locked, subtitlesPath = null,
    )

    private class FakeSettings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            settings.value = transform(settings.value)
        }
    }

    private class FakeRecordings(clips: List<Recording>, private var freeBytes: Long) : RecordingsRepository {
        private val clips = clips.toMutableList()
        val deleted = mutableListOf<String>()

        override fun observeRecordings(): Flow<List<Recording>> = MutableStateFlow(clips.toList())
        override suspend fun getRecordings() = clips.toList()
        override suspend fun createVideoFile() = "video"
        override fun secondaryVideoFile(videoPath: String) = "${videoPath}_front"
        override suspend fun createPhotoFile() = "photo"
        override suspend fun setLocked(id: String, locked: Boolean) = id
        override suspend fun delete(ids: Collection<String>) {
            ids.forEach { id ->
                val clip = clips.first { it.id == id }
                clips.remove(clip)
                freeBytes += clip.sizeBytes
                deleted += id
            }
        }
        override suspend fun storageStats() = StorageStats(clips.sumOf { it.sizeBytes }, freeBytes, 128_000_000_000L)
        override fun markInProgress(ids: Collection<String>) = Unit
        override fun clearInProgress(ids: Collection<String>) = Unit
        override fun refresh() = Unit
    }
}
