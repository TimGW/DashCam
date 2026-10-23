package com.dashcam.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.dashcam.domain.repository.ImpactDetector
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/** G-sensor: detects sudden linear acceleration spikes (collisions, hard braking). */
class AccelerometerImpactDetector(context: Context) : ImpactDetector {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    override fun impacts(thresholdG: Float): Flow<Float> = callbackFlow {
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val gravity = FloatArray(3)
        var samples = 0
        var lastImpactAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                val (x, y, z) = if (linear != null) {
                    Triple(v[0], v[1], v[2])
                } else {
                    for (i in 0..2) gravity[i] = ALPHA * gravity[i] + (1 - ALPHA) * v[i]
                    Triple(v[0] - gravity[0], v[1] - gravity[1], v[2] - gravity[2])
                }
                if (++samples < WARMUP_SAMPLES) return
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = SystemClock.elapsedRealtime()
                if (g >= thresholdG && now - lastImpactAt > DEBOUNCE_MS) {
                    lastImpactAt = now
                    trySend(g)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private companion object {
        const val ALPHA = 0.8f
        const val WARMUP_SAMPLES = 50
        const val DEBOUNCE_MS = 5_000L
    }
}
