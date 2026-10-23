package com.dashcam.domain.usecase

import com.dashcam.domain.model.DeviceHealth
import com.dashcam.domain.model.ThermalLevel
import com.dashcam.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHealthPolicyTest {

    @Test
    fun `normal conditions keep recording as is`() {
        val verdict = SessionHealthPolicy.evaluate(DeviceHealth(ThermalLevel.WARM, batteryPercent = 50, charging = false))
        assertEquals(HealthVerdict(), verdict)
    }

    @Test
    fun `hot phone drops to 720p but keeps recording`() {
        val verdict = SessionHealthPolicy.evaluate(DeviceHealth(ThermalLevel.HOT))
        assertEquals(VideoQuality.HD, verdict.qualityCap)
        assertNull(verdict.stop)
    }

    @Test
    fun `thermal emergency stops recording`() {
        val verdict = SessionHealthPolicy.evaluate(DeviceHealth(ThermalLevel.EMERGENCY))
        assertEquals(StopReason.TOO_HOT, verdict.stop)
    }

    @Test
    fun `low battery warns and empty battery stops when not charging`() {
        val low = SessionHealthPolicy.evaluate(DeviceHealth(batteryPercent = 15, charging = false))
        assertTrue(low.lowBatteryWarning)
        assertNull(low.stop)
        val empty = SessionHealthPolicy.evaluate(DeviceHealth(batteryPercent = 5, charging = false))
        assertEquals(StopReason.BATTERY_EMPTY, empty.stop)
    }

    @Test
    fun `battery level is ignored while charging`() {
        val verdict = SessionHealthPolicy.evaluate(DeviceHealth(batteryPercent = 3, charging = true))
        assertNull(verdict.stop)
        assertFalse(verdict.lowBatteryWarning)
    }
}
