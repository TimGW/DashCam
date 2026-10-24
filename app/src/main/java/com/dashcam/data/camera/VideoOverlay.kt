package com.dashcam.data.camera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.LocationSample
import com.dashcam.domain.model.SpeedUnit
import com.dashcam.domain.model.toSpeed
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Burns the date and time, optionally speed, coordinates and a label, into the recorded video
 * (not the preview, which has its own on-screen readouts), so the information survives sharing a
 * clip. Small, shadowed text drawn upright for how the video will be shown, tucked into the
 * bottom-left corner (the dual-camera inset sits top right).
 */
class VideoOverlay(
    private val settings: StateFlow<AppSettings?>,
    private val location: StateFlow<LocationSample?>,
) {
    private val thread = HandlerThread("VideoOverlay").apply { start() }

    val effect: OverlayEffect by lazy {
        OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(thread.looper)) { Log.e(TAG, "Overlay failed", it) }
            .apply { setOnDrawListener(::draw) }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        typeface = Typeface.MONOSPACE
    }

    private fun draw(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val current = settings.value ?: return true
        val crop = frame.cropRect
        val sideways = frame.rotationDegrees % 180 != 0
        val width = (if (sideways) crop.height() else crop.width()).toFloat()
        val height = (if (sideways) crop.width() else crop.height()).toFloat()
        canvas.save()
        canvas.translate(crop.left.toFloat(), crop.top.toFloat())
        // Players rotate the frame clockwise by rotationDegrees; pre-rotate the other way.
        when (frame.rotationDegrees) {
            90 -> canvas.translate(0f, crop.height().toFloat()).also { canvas.rotate(-90f) }
            180 -> canvas.translate(crop.width().toFloat(), crop.height().toFloat()).also { canvas.rotate(180f) }
            270 -> canvas.translate(crop.width().toFloat(), 0f).also { canvas.rotate(90f) }
        }
        if (frame.isMirroring) canvas.scale(-1f, 1f, width / 2, 0f)
        drawText(canvas, lines(current), width, height)
        canvas.restore()
        return true
    }

    private fun lines(settings: AppSettings): List<String> {
        val fix = location.value?.takeIf { System.currentTimeMillis() - it.timeMillis < STALE_MS }
        val speed = fix?.speedMetersPerSecond?.takeIf { settings.overlaySpeed }?.let {
            val unit = if (settings.speedUnit == SpeedUnit.KMH) "km/h" else "mph"
            "${it.toSpeed(settings.speedUnit)} $unit"
        }
        return buildList {
            add(listOfNotNull(dateFormat.format(Date()), speed).joinToString("   "))
            if (settings.overlayLocation && fix != null) {
                add("%.5f, %.5f".format(Locale.US, fix.latitude, fix.longitude))
            }
            settings.overlayLabel.trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    /** Bottom-left, last line closest to the corner; a soft shadow keeps it readable anywhere. */
    private fun drawText(canvas: Canvas, lines: List<String>, width: Float, height: Float) {
        val shortSide = minOf(width, height)
        textPaint.textSize = shortSide * TEXT_SIZE
        textPaint.setShadowLayer(textPaint.textSize * 0.15f, 0f, 0f, Color.argb(200, 0, 0, 0))
        val margin = shortSide * MARGIN
        val lineHeight = textPaint.fontSpacing
        val baseline = height - margin - textPaint.descent()
        lines.asReversed().forEachIndexed { index, line ->
            canvas.drawText(line, margin, baseline - index * lineHeight, textPaint)
        }
    }

    private companion object {
        const val TAG = "VideoOverlay"
        const val STALE_MS = 5_000L
        /** Relative to the frame's short side. */
        const val TEXT_SIZE = 0.022f
        const val MARGIN = 0.012f
    }
}
