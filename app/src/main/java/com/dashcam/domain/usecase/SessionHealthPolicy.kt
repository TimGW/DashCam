package com.dashcam.domain.usecase

import com.dashcam.domain.model.DeviceHealth
import com.dashcam.domain.model.ThermalLevel
import com.dashcam.domain.model.VideoQuality

/** What a recording session should do about the phone's temperature and battery. */
data class HealthVerdict(
    val stop: StopReason? = null,
    /** Record at most this quality from the next clip on, to cool down. */
    val qualityCap: VideoQuality? = null,
    val lowBatteryWarning: Boolean = false,
)

enum class StopReason { TOO_HOT, BATTERY_EMPTY }

object SessionHealthPolicy {
    const val LOW_BATTERY_PERCENT = 15
    const val EMPTY_BATTERY_PERCENT = 5

    /**
     * Stops before Android would kill the camera (or the phone dies), so the last clip is saved
     * properly; drops to 720p when the phone gets hot, since encoding is a major heat source.
     */
    fun evaluate(health: DeviceHealth): HealthVerdict {
        val battery = health.batteryPercent?.takeUnless { health.charging }
        return HealthVerdict(
            stop = when {
                health.thermal >= ThermalLevel.EMERGENCY -> StopReason.TOO_HOT
                battery != null && battery <= EMPTY_BATTERY_PERCENT -> StopReason.BATTERY_EMPTY
                else -> null
            },
            qualityCap = VideoQuality.HD.takeIf { health.thermal >= ThermalLevel.HOT },
            lowBatteryWarning = battery != null && battery <= LOW_BATTERY_PERCENT,
        )
    }
}
