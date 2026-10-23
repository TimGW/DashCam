package com.dashcam.data.recorder

import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.LocationSample
import com.dashcam.domain.model.SpeedUnit
import com.dashcam.domain.model.toSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes per-second subtitles (date, speed, coordinates) as an .srt next to the video and,
 * optionally, a GPX track. Players such as VLC or the in-app player show the .srt automatically.
 */
class TelemetryWriter(
    private val videoPath: String,
    private val settings: AppSettings,
    private val location: StateFlow<LocationSample?>,
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val gpxTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        val base = videoPath.substringBeforeLast('.')
        val srt = if (settings.writeSubtitles) File("$base.srt").bufferedWriter() else null
        val gpx = if (settings.writeGpx) File("$base.gpx").bufferedWriter() else null
        val startedAt = System.currentTimeMillis()
        var second = 0
        var lastGpxFix = 0L
        try {
            gpx?.write(GPX_HEADER)
            while (currentCoroutineContext().isActive) {
                val fix = location.value?.takeIf { System.currentTimeMillis() - it.timeMillis < STALE_MS }
                srt?.writeEntry(second, fix)
                if (gpx != null && fix != null && fix.timeMillis != lastGpxFix) {
                    gpx.writeTrackPoint(fix)
                    lastGpxFix = fix.timeMillis
                }
                srt?.flush()
                gpx?.flush()
                second++
                delay(startedAt + second * 1_000L - System.currentTimeMillis())
            }
        } finally {
            gpx?.write(GPX_FOOTER)
            srt?.close()
            gpx?.close()
        }
    }

    private fun Writer.writeEntry(second: Int, fix: LocationSample?) {
        val parts = buildList {
            add(dateFormat.format(Date()))
            fix?.speedMetersPerSecond?.let {
                val unit = if (settings.speedUnit == SpeedUnit.KMH) "km/h" else "mph"
                add("${it.toSpeed(settings.speedUnit)} $unit")
            }
            fix?.let { add("%.5f, %.5f".format(Locale.US, it.latitude, it.longitude)) }
        }
        write("${second + 1}\n${srtTime(second)} --> ${srtTime(second + 1)}\n")
        write(parts.joinToString("  ·  "))
        write("\n\n")
    }

    private fun Writer.writeTrackPoint(fix: LocationSample) {
        write("""      <trkpt lat="%.7f" lon="%.7f">""".format(Locale.US, fix.latitude, fix.longitude))
        fix.altitudeMeters?.let { write("<ele>%.1f</ele>".format(Locale.US, it)) }
        write("<time>${gpxTimeFormat.format(Date(fix.timeMillis))}</time>")
        fix.speedMetersPerSecond?.let { write("<speed>%.2f</speed>".format(Locale.US, it)) }
        write("</trkpt>\n")
    }

    private fun srtTime(second: Int) =
        "%02d:%02d:%02d,000".format(Locale.US, second / 3600, second / 60 % 60, second % 60)

    private companion object {
        const val STALE_MS = 5_000L
        const val GPX_HEADER = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="DashCam" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
"""
        const val GPX_FOOTER = """    </trkseg>
  </trk>
</gpx>
"""
    }
}
