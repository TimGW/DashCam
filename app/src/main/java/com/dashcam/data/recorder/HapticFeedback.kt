package com.dashcam.data.recorder

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticFeedback(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun tick() = vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))

    fun alert() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))

    private fun vibrate(effect: VibrationEffect) {
        vibrator?.takeIf { it.hasVibrator() }?.vibrate(effect)
    }
}
