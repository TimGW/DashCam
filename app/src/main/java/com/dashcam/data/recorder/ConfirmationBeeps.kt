package com.dashcam.data.recorder

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A few short beeps to confirm a voice command without looking at the phone. They play on the
 * media stream, so they come through the car speakers when the phone is connected over Bluetooth.
 */
class ConfirmationBeeps {
    private val handler = Handler(Looper.getMainLooper())

    fun play() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No tone generator available", e)
            return
        }
        repeat(COUNT) { i ->
            handler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS) }, i * INTERVAL_MS)
        }
        handler.postDelayed(tone::release, COUNT * INTERVAL_MS + BEEP_MS)
    }

    private companion object {
        const val TAG = "ConfirmationBeeps"
        const val VOLUME = 90
        const val COUNT = 3
        const val BEEP_MS = 120
        const val INTERVAL_MS = 200L
    }
}
