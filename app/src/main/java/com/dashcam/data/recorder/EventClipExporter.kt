package com.dashcam.data.recorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts an event clip out of finished loop clips without re-encoding: copies the samples between
 * two wall-clock times from one or more consecutive clips into a new MP4. The video starts at
 * the last key frame before the start time, so the event clip plays from its first frame.
 */
class EventClipExporter {

    /** A finished clip and the wall-clock time its recording started. */
    data class Source(val path: String, val startedAtMillis: Long)

    /** Returns false (and deletes [output]) when nothing could be copied. */
    fun export(sources: List<Source>, fromMillis: Long, toMillis: Long, output: File): Boolean {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var written = 0
        return try {
            val muxerTracks = mutableMapOf<String, Int>()
            var originUs: Long? = null
            for (source in sources) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source.path)
                    val trackKinds = mutableMapOf<Int, String>()
                    var bufferSize = DEFAULT_BUFFER_BYTES
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val kind = format.getString(MediaFormat.KEY_MIME)?.substringBefore('/') ?: continue
                        if (kind != VIDEO && kind != AUDIO) continue
                        extractor.selectTrack(i)
                        trackKinds[i] = kind
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                        if (!started && kind !in muxerTracks) {
                            muxerTracks[kind] = muxer.addTrack(format)
                            if (kind == VIDEO && format.containsKey(MediaFormat.KEY_ROTATION)) {
                                muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                            }
                        }
                    }
                    if (!started) {
                        check(VIDEO in muxerTracks) { "No video track in ${source.path}" }
                        muxer.start()
                        started = true
                    }
                    val offsetUs = (fromMillis - source.startedAtMillis) * 1000
                    if (offsetUs > 0) extractor.seekTo(offsetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val buffer = ByteBuffer.allocateDirect(bufferSize)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val kind = trackKinds[extractor.sampleTrackIndex]
                        val wallUs = source.startedAtMillis * 1000 + extractor.sampleTime
                        val isKey = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        // Begin at a video key frame; drop anything before it and after the end.
                        if (originUs == null && kind == VIDEO && isKey) originUs = wallUs
                        val origin = originUs
                        val track = kind?.let(muxerTracks::get)
                        if (origin != null && track != null && wallUs >= origin && wallUs <= toMillis * 1000) {
                            info.set(0, size, wallUs - origin, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(track, buffer, info)
                            written++
                        }
                        extractor.advance()
                    }
                } finally {
                    extractor.release()
                }
            }
            written > 0
        } catch (e: Exception) {
            Log.e(TAG, "Event clip export failed", e)
            false
        } finally {
            runCatching { if (started) muxer.stop() }
            muxer.release()
        }.also { ok -> if (!ok) output.delete() }
    }

    private companion object {
        const val TAG = "EventClipExporter"
        const val VIDEO = "video"
        const val AUDIO = "audio"
        const val DEFAULT_BUFFER_BYTES = 4 * 1024 * 1024
    }
}
