package com.dashcam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.CameraFacing
import com.dashcam.domain.model.GSensorSensitivity
import com.dashcam.domain.model.NavigationStopDelay
import com.dashcam.domain.model.SpeedUnit
import com.dashcam.domain.model.ThemeMode
import com.dashcam.domain.model.VideoAspectRatio
import com.dashcam.domain.model.VideoQuality
import com.dashcam.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(context: Context) : SettingsRepository {

    private val dataStore = context.applicationContext.dataStore

    override val settings: Flow<AppSettings> =
        dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            videoQuality = enumOf(this[Keys.videoQuality], defaults.videoQuality),
            cameraFacing = enumOf(this[Keys.cameraFacing], defaults.cameraFacing),
            separateCameraFiles = this[Keys.separateCameraFiles] ?: defaults.separateCameraFiles,
            aspectRatio = enumOf(this[Keys.aspectRatio], defaults.aspectRatio),
            wideAngle = this[Keys.wideAngle] ?: defaults.wideAngle,
            focusMode = enumOf(this[Keys.focusMode], defaults.focusMode),
            bitrate = enumOf(this[Keys.bitrate], defaults.bitrate),
            frameRate = this[Keys.frameRate] ?: defaults.frameRate,
            stabilization = this[Keys.stabilization] ?: defaults.stabilization,
            videoOverlay = this[Keys.videoOverlay] ?: defaults.videoOverlay,
            overlaySpeed = this[Keys.overlaySpeed] ?: defaults.overlaySpeed,
            overlayLocation = this[Keys.overlayLocation] ?: defaults.overlayLocation,
            overlayLabel = this[Keys.overlayLabel] ?: defaults.overlayLabel,
            eventClipSeconds = this[Keys.eventClipSeconds] ?: defaults.eventClipSeconds,
            remoteButtonMarksEvent = this[Keys.remoteButtonMarksEvent] ?: defaults.remoteButtonMarksEvent,
            recordAudio = this[Keys.recordAudio] ?: defaults.recordAudio,
            segmentMinutes = this[Keys.segmentMinutes] ?: defaults.segmentMinutes,
            loopRecording = this[Keys.loopRecording] ?: defaults.loopRecording,
            maxStorageGb = this[Keys.maxStorageGb] ?: defaults.maxStorageGb,
            gSensorEnabled = this[Keys.gSensorEnabled] ?: defaults.gSensorEnabled,
            gSensorSensitivity = enumOf(this[Keys.gSensorSensitivity], defaults.gSensorSensitivity),
            vibrateOnImpact = this[Keys.vibrateOnImpact] ?: defaults.vibrateOnImpact,
            writeGpx = this[Keys.writeGpx] ?: defaults.writeGpx,
            speedUnit = enumOf(this[Keys.speedUnit], defaults.speedUnit),
            autoStartOnLaunch = this[Keys.autoStartOnLaunch] ?: defaults.autoStartOnLaunch,
            autoRecordOnNavigation = this[Keys.autoRecordOnNavigation] ?: defaults.autoRecordOnNavigation,
            navigationStopDelay = enumOf(this[Keys.navigationStopDelay], defaults.navigationStopDelay),
            keepScreenOn = this[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            pictureInPicture = this[Keys.pictureInPicture] ?: defaults.pictureInPicture,
            vibrateOnStartStop = this[Keys.vibrateOnStartStop] ?: defaults.vibrateOnStartStop,
            themeMode = enumOf(this[Keys.themeMode], defaults.themeMode),
            dynamicColor = this[Keys.dynamicColor] ?: defaults.dynamicColor,
            pinHash = this[Keys.pinHash],
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.videoQuality] = s.videoQuality.name
        this[Keys.cameraFacing] = s.cameraFacing.name
        this[Keys.separateCameraFiles] = s.separateCameraFiles
        this[Keys.aspectRatio] = s.aspectRatio.name
        this[Keys.wideAngle] = s.wideAngle
        this[Keys.focusMode] = s.focusMode.name
        this[Keys.bitrate] = s.bitrate.name
        this[Keys.frameRate] = s.frameRate
        this[Keys.stabilization] = s.stabilization
        this[Keys.videoOverlay] = s.videoOverlay
        this[Keys.overlaySpeed] = s.overlaySpeed
        this[Keys.overlayLocation] = s.overlayLocation
        this[Keys.overlayLabel] = s.overlayLabel
        this[Keys.eventClipSeconds] = s.eventClipSeconds
        this[Keys.remoteButtonMarksEvent] = s.remoteButtonMarksEvent
        this[Keys.recordAudio] = s.recordAudio
        this[Keys.segmentMinutes] = s.segmentMinutes
        this[Keys.loopRecording] = s.loopRecording
        this[Keys.maxStorageGb] = s.maxStorageGb
        this[Keys.gSensorEnabled] = s.gSensorEnabled
        this[Keys.gSensorSensitivity] = s.gSensorSensitivity.name
        this[Keys.vibrateOnImpact] = s.vibrateOnImpact
        this[Keys.writeGpx] = s.writeGpx
        this[Keys.speedUnit] = s.speedUnit.name
        this[Keys.autoStartOnLaunch] = s.autoStartOnLaunch
        this[Keys.autoRecordOnNavigation] = s.autoRecordOnNavigation
        this[Keys.navigationStopDelay] = s.navigationStopDelay.name
        this[Keys.keepScreenOn] = s.keepScreenOn
        this[Keys.pictureInPicture] = s.pictureInPicture
        this[Keys.vibrateOnStartStop] = s.vibrateOnStartStop
        this[Keys.themeMode] = s.themeMode.name
        this[Keys.dynamicColor] = s.dynamicColor
        if (s.pinHash == null) remove(Keys.pinHash) else this[Keys.pinHash] = s.pinHash
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private object Keys {
        val videoQuality = stringPreferencesKey("video_quality")
        val cameraFacing = stringPreferencesKey("camera_facing")
        val separateCameraFiles = booleanPreferencesKey("separate_camera_files")
        val aspectRatio = stringPreferencesKey("aspect_ratio")
        val wideAngle = booleanPreferencesKey("wide_angle")
        val focusMode = stringPreferencesKey("focus_mode")
        val bitrate = stringPreferencesKey("bitrate")
        val frameRate = intPreferencesKey("frame_rate")
        val stabilization = booleanPreferencesKey("stabilization")
        val videoOverlay = booleanPreferencesKey("video_overlay")
        val overlaySpeed = booleanPreferencesKey("overlay_speed")
        // New key: coordinates are now opt-in, so earlier "on" defaults don't carry over.
        val overlayLocation = booleanPreferencesKey("overlay_coordinates")
        val overlayLabel = stringPreferencesKey("overlay_label")
        val eventClipSeconds = intPreferencesKey("event_clip_seconds")
        val remoteButtonMarksEvent = booleanPreferencesKey("remote_button_marks_event")
        val recordAudio = booleanPreferencesKey("record_audio")
        val segmentMinutes = intPreferencesKey("segment_minutes")
        val loopRecording = booleanPreferencesKey("loop_recording")
        val maxStorageGb = intPreferencesKey("max_storage_gb")
        val gSensorEnabled = booleanPreferencesKey("g_sensor_enabled")
        val gSensorSensitivity = stringPreferencesKey("g_sensor_sensitivity")
        val vibrateOnImpact = booleanPreferencesKey("vibrate_on_impact")
        val writeGpx = booleanPreferencesKey("write_gpx")
        val speedUnit = stringPreferencesKey("speed_unit")
        val autoStartOnLaunch = booleanPreferencesKey("auto_start_on_launch")
        val autoRecordOnNavigation = booleanPreferencesKey("auto_record_on_navigation")
        val navigationStopDelay = stringPreferencesKey("navigation_stop_delay")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val pictureInPicture = booleanPreferencesKey("picture_in_picture")
        val vibrateOnStartStop = booleanPreferencesKey("vibrate_on_start_stop")
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val pinHash = stringPreferencesKey("pin_hash")
    }
}

