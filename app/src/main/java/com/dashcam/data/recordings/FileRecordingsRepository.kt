package com.dashcam.data.recordings

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.domain.model.StorageStats
import com.dashcam.domain.repository.RecordingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores recordings in the app-specific external storage (no storage permission needed).
 * Lock state is encoded in the file name so it survives reinstalls of the database-free app;
 * sidecar telemetry files (.srt/.gpx) share the video's base name. A front camera video recorded
 * alongside the main one is a separate recording with a "_front" suffix; event clips cut around
 * an impact or marked moment get an "_event" suffix.
 */
class FileRecordingsRepository(context: Context) : RecordingsRepository {

    private val videoDir = mediaDir(context, Environment.DIRECTORY_MOVIES)
    private val photoDir = mediaDir(context, Environment.DIRECTORY_PICTURES)
    private val refreshSignal = MutableStateFlow(0L)
    private val durationCache = ConcurrentHashMap<String, Pair<Long, Long>>()

    private val inProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun observeRecordings(): Flow<List<Recording>> =
        refreshSignal.map { scan() }.flowOn(Dispatchers.IO)

    override suspend fun getRecordings(): List<Recording> = withContext(Dispatchers.IO) { scan() }

    override suspend fun createVideoFile(): String = withContext(Dispatchers.IO) {
        val dir = videoDir.mkdirsIfNeeded()
        val base = uniqueVideoBase(Date()) { candidate ->
            File(dir, "$candidate.$VIDEO_EXT").exists() || File(dir, "$candidate$LOCKED_SUFFIX.$VIDEO_EXT").exists()
        }
        File(dir, "$base.$VIDEO_EXT").absolutePath
    }

    override suspend fun createEventFile(eventAtMillis: Long): String = withContext(Dispatchers.IO) {
        val dir = videoDir.mkdirsIfNeeded()
        val base = uniqueVideoBase(Date(eventAtMillis)) { File(dir, "$it$EVENT_SUFFIX$LOCKED_SUFFIX.$VIDEO_EXT").exists() }
        File(dir, "$base$EVENT_SUFFIX$LOCKED_SUFFIX.$VIDEO_EXT").absolutePath
    }

    override fun secondaryVideoFile(videoPath: String): String {
        val file = File(videoPath)
        return File(file.parentFile, "${file.nameWithoutExtension}$FRONT_SUFFIX.${file.extension}").absolutePath
    }

    override suspend fun createPhotoFile(): String = withContext(Dispatchers.IO) {
        File(photoDir.mkdirsIfNeeded(), "$PREFIX${timestamp(PHOTO_PATTERN)}.$PHOTO_EXT").absolutePath
    }

    override suspend fun setLocked(id: String, locked: Boolean): String = withContext(Dispatchers.IO) {
        val file = File(id)
        val base = file.nameWithoutExtension
        val isLocked = base.endsWith(LOCKED_SUFFIX)
        if (isLocked == locked || !file.exists()) return@withContext id
        val newBase = if (locked) base + LOCKED_SUFFIX else base.removeSuffix(LOCKED_SUFFIX)
        siblings(file).forEach { sibling ->
            sibling.renameTo(File(sibling.parentFile, newBase + "." + sibling.extension))
        }
        File(file.parentFile, "$newBase.${file.extension}").absolutePath
    }

    override suspend fun delete(ids: Collection<String>) = withContext(Dispatchers.IO) {
        ids.forEach { id -> siblings(File(id)).forEach(File::delete) }
    }

    override suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        val dir = videoDir.mkdirsIfNeeded()
        StorageStats(
            recordingsBytes = listOf(dir, photoDir)
                .flatMap { it.listFiles().orEmpty().asList() }
                .sumOf { it.length() },
            freeBytes = dir.usableSpace,
            totalBytes = dir.totalSpace,
        )
    }

    override fun markInProgress(ids: Collection<String>) {
        inProgress.addAll(ids)
        refresh()
    }

    override fun clearInProgress(ids: Collection<String>) {
        inProgress.removeAll(ids.toSet())
        refresh()
    }

    override fun refresh() {
        refreshSignal.value = System.nanoTime()
    }

    private fun scan(): List<Recording> {
        val videos = videoDir.listFiles { f -> f.extension == VIDEO_EXT }.orEmpty()
            .filter { it.absolutePath !in inProgress && it.length() > 0 }
            .map { it.toRecording(RecordingType.VIDEO) }
        val photos = photoDir.listFiles { f -> f.extension == PHOTO_EXT }.orEmpty()
            .filter { it.length() > 0 }
            .map { it.toRecording(RecordingType.PHOTO) }
        return videos + photos
    }

    private fun File.toRecording(type: RecordingType): Recording {
        val subtitles = File(parentFile, "$nameWithoutExtension.srt")
        return Recording(
            id = absolutePath,
            name = name,
            type = type,
            createdAtMillis = parseTimestamp(nameWithoutExtension) ?: lastModified(),
            sizeBytes = length(),
            durationMillis = if (type == RecordingType.VIDEO) duration(this) else null,
            isLocked = nameWithoutExtension.endsWith(LOCKED_SUFFIX),
            subtitlesPath = subtitles.takeIf { it.exists() }?.absolutePath,
        )
    }

    private fun duration(file: File): Long? {
        val cached = durationCache[file.absolutePath]
        if (cached != null && cached.first == file.lastModified()) return cached.second
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.also { durationCache[file.absolutePath] = file.lastModified() to it }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun siblings(file: File): List<File> {
        val base = file.nameWithoutExtension
        return file.parentFile?.listFiles { f -> f.nameWithoutExtension == base }.orEmpty().toList()
    }

    private fun parseTimestamp(base: String): Long? {
        val stamp = base.removePrefix(PREFIX).removeSuffix(LOCKED_SUFFIX).removeSuffix(FRONT_SUFFIX).removeSuffix(EVENT_SUFFIX)
        val pattern = if (stamp.length == PHOTO_PATTERN.length) PHOTO_PATTERN else VIDEO_PATTERN
        return runCatching { SimpleDateFormat(pattern, Locale.US).parse(stamp)?.time }.getOrNull()
    }

    private fun timestamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())

    private fun File.mkdirsIfNeeded(): File = apply { if (!exists()) mkdirs() }

    internal companion object {
        private const val PREFIX = "DC_"
        private const val LOCKED_SUFFIX = "_locked"
        private const val FRONT_SUFFIX = "_front"
        private const val EVENT_SUFFIX = "_event"
        private const val VIDEO_EXT = "mp4"
        private const val PHOTO_EXT = "jpg"
        private const val VIDEO_PATTERN = "yyyyMMdd_HHmmss"
        private const val PHOTO_PATTERN = "yyyyMMdd_HHmmss_SSS"

        /**
         * Video names only have second precision; when a clip with that name already exists (a
         * recording restarted within the same second), use the next free second instead.
         */
        internal fun uniqueVideoBase(date: Date, taken: (String) -> Boolean): String {
            val format = SimpleDateFormat(VIDEO_PATTERN, Locale.US)
            var time = date.time
            while (true) {
                val base = PREFIX + format.format(Date(time))
                if (!taken(base)) return base
                time += 1_000
            }
        }

        private fun mediaDir(context: Context, type: String): File =
            File(context.getExternalFilesDir(type) ?: File(context.filesDir, type), "DashCam")
    }
}
