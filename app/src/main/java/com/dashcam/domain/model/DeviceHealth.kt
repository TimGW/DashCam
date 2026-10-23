package com.dashcam.domain.model

/** How hot the phone runs, from Android's thermal status. */
enum class ThermalLevel { NORMAL, WARM, HOT, CRITICAL, EMERGENCY }

data class DeviceHealth(
    val thermal: ThermalLevel = ThermalLevel.NORMAL,
    /** 0..100, or null when unknown. */
    val batteryPercent: Int? = null,
    val charging: Boolean = true,
)
