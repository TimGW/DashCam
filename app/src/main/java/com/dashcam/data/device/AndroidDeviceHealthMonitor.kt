package com.dashcam.data.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.dashcam.domain.model.DeviceHealth
import com.dashcam.domain.model.ThermalLevel
import com.dashcam.domain.repository.DeviceHealthMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Thermal status from PowerManager and battery state from the sticky battery broadcast. */
class AndroidDeviceHealthMonitor(private val context: Context) : DeviceHealthMonitor {

    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val thermal: Flow<ThermalLevel> = callbackFlow {
        val listener = PowerManager.OnThermalStatusChangedListener { trySend(it.toThermalLevel()) }
        trySend(powerManager.currentThermalStatus.toThermalLevel())
        powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
        awaitClose { powerManager.removeThermalStatusListener(listener) }
    }

    private val battery: Flow<Pair<Int?, Boolean>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.batteryState())
            }
        }
        // Sticky broadcast: registering returns the current state right away.
        val current = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        current?.let { trySend(it.batteryState()) }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    override val health: Flow<DeviceHealth> = combine(thermal, battery) { thermal, (percent, charging) ->
        DeviceHealth(thermal, percent, charging)
    }.distinctUntilChanged()

    private fun Intent.batteryState(): Pair<Int?, Boolean> {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    private fun Int.toThermalLevel() = when (this) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NORMAL
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HOT
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        else -> ThermalLevel.EMERGENCY
    }
}
