package com.dashcam.presentation.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CarCrash
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.datasource.CollectionPreviewParameterProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dashcam.R
import com.dashcam.data.camera.supportsDualCamera
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.CameraFacing
import com.dashcam.domain.model.EventClipSecondsOptions
import com.dashcam.domain.model.FocusMode
import com.dashcam.domain.model.GSensorSensitivity
import com.dashcam.domain.model.VideoBitrate
import com.dashcam.domain.model.VideoEncoding
import com.dashcam.domain.model.NavigationStopDelay
import com.dashcam.domain.model.SpeedUnit
import com.dashcam.domain.model.ThemeMode
import com.dashcam.domain.model.VideoAspectRatio
import com.dashcam.domain.model.VideoQuality
import com.dashcam.presentation.common.containerViewModelFactory
import com.dashcam.presentation.theme.DashCamPreviewTheme
import com.dashcam.service.NavigationListenerService

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenEventLog: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = containerViewModelFactory { SettingsViewModel(it.settingsRepository, it.managePin) },
    ),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onOpenEventLog = onOpenEventLog,
        onUpdate = viewModel::update,
        onChangePin = viewModel::changePin,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    settings: AppSettings?,
    onBack: () -> Unit,
    onOpenEventLog: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onChangePin: suspend (currentPin: String?, newPin: String?) -> Boolean,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var pinDialog by remember { mutableStateOf<PinDialogMode?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val current = settings ?: return@Scaffold
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            settingsContent(current, onUpdate, onPinAction = { pinDialog = it }, onOpenEventLog = onOpenEventLog)
        }
    }

    pinDialog?.let { mode ->
        PinDialog(mode = mode, onSubmit = onChangePin, onDismiss = { pinDialog = null })
    }
}

private fun LazyListScope.settingsContent(
    s: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onPinAction: (PinDialogMode) -> Unit,
    onOpenEventLog: () -> Unit,
) {
    item { SectionHeader(stringResource(R.string.settings_section_video)) }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_resolution),
            icon = Icons.Outlined.HighQuality,
            options = VideoQuality.entries,
            selected = s.videoQuality,
            label = { stringResource(it.labelRes()) },
            onSelect = { q -> update { it.copy(videoQuality = q) } },
        )
    }
    item { CameraSetting(s, update) }
    item { SeparateCameraFilesSetting(s, update) }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_aspect_ratio),
            icon = Icons.Outlined.AspectRatio,
            options = VideoAspectRatio.entries,
            selected = s.aspectRatio,
            label = { stringResource(it.labelRes()) },
            onSelect = { r -> update { it.copy(aspectRatio = r) } },
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_wide_angle),
            summary = stringResource(R.string.settings_wide_angle_summary),
            icon = Icons.Outlined.ZoomOutMap,
            checked = s.wideAngle,
            onCheckedChange = { v -> update { it.copy(wideAngle = v) } },
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_focus),
            icon = Icons.Outlined.CenterFocusWeak,
            options = FocusMode.entries,
            selected = s.focusMode,
            label = { stringResource(if (it == FocusMode.AUTO) R.string.focus_auto else R.string.focus_infinity) },
            onSelect = { f -> update { it.copy(focusMode = f) } },
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_bitrate),
            icon = Icons.Outlined.DataUsage,
            options = VideoBitrate.entries,
            selected = s.bitrate,
            label = { b ->
                val name = stringResource(b.labelRes())
                if (b == VideoBitrate.AUTO) name
                else stringResource(R.string.bitrate_value, name, VideoEncoding.megabitsPerSecond(s.cameraSetup.copy(bitrate = b)))
            },
            onSelect = { b -> update { it.copy(bitrate = b) } },
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_frame_rate),
            icon = Icons.Outlined.SlowMotionVideo,
            options = listOf(30, 60),
            selected = s.frameRate,
            label = { stringResource(R.string.frame_rate_fps, it) },
            onSelect = { fps -> update { it.copy(frameRate = fps) } },
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_stabilization),
            summary = stringResource(R.string.settings_stabilization_summary),
            icon = Icons.Outlined.Straighten,
            checked = s.stabilization,
            onCheckedChange = { v -> update { it.copy(stabilization = v) } },
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_overlay),
            summary = stringResource(R.string.settings_overlay_summary),
            icon = Icons.Outlined.TextFields,
            checked = s.videoOverlay,
            onCheckedChange = { v -> update { it.copy(videoOverlay = v) } },
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_overlay_speed),
            icon = Icons.Outlined.Speed,
            checked = s.overlaySpeed,
            onCheckedChange = { v -> update { it.copy(overlaySpeed = v) } },
            enabled = s.videoOverlay,
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_overlay_location),
            icon = Icons.Outlined.LocationOn,
            checked = s.overlayLocation,
            onCheckedChange = { v -> update { it.copy(overlayLocation = v) } },
            enabled = s.videoOverlay,
        )
    }
    item {
        TextSetting(
            title = stringResource(R.string.settings_overlay_label),
            icon = Icons.AutoMirrored.Outlined.Label,
            value = s.overlayLabel,
            emptyLabel = stringResource(R.string.settings_overlay_label_empty),
            onValueChange = { v -> update { it.copy(overlayLabel = v) } },
            enabled = s.videoOverlay,
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_audio),
            icon = Icons.Outlined.Mic,
            checked = s.recordAudio,
            onCheckedChange = { v -> update { it.copy(recordAudio = v) } },
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_clip_length),
            icon = Icons.Outlined.Timer,
            options = AppSettings.SegmentMinuteOptions,
            selected = s.segmentMinutes,
            label = { pluralStringResource(R.plurals.minutes, it, it) },
            onSelect = { m -> update { it.copy(segmentMinutes = m) } },
        )
    }

    item { SectionHeader(stringResource(R.string.settings_section_storage)) }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_loop_recording),
            summary = stringResource(R.string.settings_loop_recording_summary),
            icon = Icons.Outlined.Loop,
            checked = s.loopRecording,
            onCheckedChange = { v -> update { it.copy(loopRecording = v) } },
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_max_storage),
            icon = Icons.Outlined.Storage,
            options = AppSettings.MaxStorageGbOptions,
            selected = s.maxStorageGb,
            label = { gb ->
                if (gb == 0) {
                    stringResource(R.string.storage_unlimited)
                } else {
                    val minutes = (gb * 1_000_000_000L / VideoEncoding.bytesPerMinute(s.cameraSetup)).toInt()
                    if (minutes < 60) {
                        stringResource(R.string.storage_gb_estimate_minutes, gb, minutes)
                    } else {
                        stringResource(R.string.storage_gb_estimate, gb, minutes / 60, minutes % 60)
                    }
                }
            },
            onSelect = { gb -> update { it.copy(maxStorageGb = gb) } },
        )
    }
    item {
        ActionSetting(
            title = stringResource(R.string.settings_storage_location),
            summary = stringResource(R.string.settings_storage_location_summary),
            icon = Icons.Outlined.Folder,
        )
    }

    item { SectionHeader(stringResource(R.string.settings_section_safety)) }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_g_sensor),
            summary = stringResource(R.string.settings_g_sensor_summary),
            icon = Icons.Outlined.CarCrash,
            checked = s.gSensorEnabled,
            onCheckedChange = { v -> update { it.copy(gSensorEnabled = v) } },
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_g_sensor_sensitivity),
            icon = Icons.Outlined.Sensors,
            options = GSensorSensitivity.entries,
            selected = s.gSensorSensitivity,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> update { it.copy(gSensorSensitivity = v) } },
            enabled = s.gSensorEnabled,
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_vibrate_on_impact),
            icon = Icons.Outlined.Vibration,
            checked = s.vibrateOnImpact,
            onCheckedChange = { v -> update { it.copy(vibrateOnImpact = v) } },
            enabled = s.gSensorEnabled,
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_event_clip),
            icon = Icons.Outlined.ContentCut,
            options = EventClipSecondsOptions,
            selected = s.eventClipSeconds,
            label = {
                if (it == 0) stringResource(R.string.event_clip_off) else stringResource(R.string.event_clip_seconds, it)
            },
            onSelect = { v -> update { it.copy(eventClipSeconds = v) } },
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_remote_button),
            summary = stringResource(R.string.settings_remote_button_summary),
            icon = Icons.Outlined.SettingsRemote,
            checked = s.remoteButtonMarksEvent,
            onCheckedChange = { v -> update { it.copy(remoteButtonMarksEvent = v) } },
        )
    }

    item { SectionHeader(stringResource(R.string.settings_section_location)) }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_gpx),
            summary = stringResource(R.string.settings_gpx_summary),
            icon = Icons.Outlined.Route,
            checked = s.writeGpx,
            onCheckedChange = { v -> update { it.copy(writeGpx = v) } },
        )
    }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_speed_unit),
            icon = Icons.Outlined.Speed,
            options = SpeedUnit.entries,
            selected = s.speedUnit,
            label = { stringResource(if (it == SpeedUnit.KMH) R.string.unit_kmh else R.string.unit_mph) },
            onSelect = { u -> update { it.copy(speedUnit = u) } },
        )
    }

    item { SectionHeader(stringResource(R.string.settings_section_behavior)) }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_auto_start),
            summary = stringResource(R.string.settings_auto_start_summary),
            icon = Icons.Outlined.PlayCircle,
            checked = s.autoStartOnLaunch,
            onCheckedChange = { v -> update { it.copy(autoStartOnLaunch = v) } },
        )
    }
    item { NavigationRecordingSettings(s, update) }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_keep_screen_on),
            icon = Icons.Outlined.Visibility,
            checked = s.keepScreenOn,
            onCheckedChange = { v -> update { it.copy(keepScreenOn = v) } },
        )
    }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_vibrate_start_stop),
            icon = Icons.Outlined.Vibration,
            checked = s.vibrateOnStartStop,
            onCheckedChange = { v -> update { it.copy(vibrateOnStartStop = v) } },
        )
    }
    item { PictureInPictureSetting(s, update) }
    item { BatteryOptimizationSetting() }

    item { SectionHeader(stringResource(R.string.settings_section_privacy)) }
    if (s.isPinEnabled) {
        item {
            ActionSetting(
                title = stringResource(R.string.settings_pin_change),
                icon = Icons.Outlined.Password,
                onClick = { onPinAction(PinDialogMode.CHANGE) },
            )
        }
        item {
            ActionSetting(
                title = stringResource(R.string.settings_pin_remove),
                icon = Icons.Outlined.LockOpen,
                onClick = { onPinAction(PinDialogMode.REMOVE) },
            )
        }
    } else {
        item {
            ActionSetting(
                title = stringResource(R.string.settings_pin_set),
                summary = stringResource(R.string.settings_pin_summary),
                icon = Icons.Outlined.Lock,
                onClick = { onPinAction(PinDialogMode.SET) },
            )
        }
    }

    item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_theme),
            icon = if (s.themeMode == ThemeMode.DARK) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
            options = ThemeMode.entries,
            selected = s.themeMode,
            label = { stringResource(it.labelRes()) },
            onSelect = { t -> update { it.copy(themeMode = t) } },
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        item {
            SwitchSetting(
                title = stringResource(R.string.settings_dynamic_color),
                summary = stringResource(R.string.settings_dynamic_color_summary),
                icon = Icons.Outlined.Colorize,
                checked = s.dynamicColor,
                onCheckedChange = { v -> update { it.copy(dynamicColor = v) } },
            )
        }
    }

    item { SectionHeader(stringResource(R.string.settings_section_about)) }
    item {
        ActionSetting(
            title = stringResource(R.string.event_log_title),
            summary = stringResource(R.string.settings_event_log_summary),
            icon = Icons.Outlined.History,
            onClick = onOpenEventLog,
        )
    }
    item { VersionSetting() }
}

@Composable
private fun CameraSetting(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    // "Both cameras" is only offered on devices that can stream front and back at once.
    val options = remember {
        if (context.supportsDualCamera()) CameraFacing.entries else CameraFacing.entries - CameraFacing.BOTH
    }
    ChoiceSetting(
        title = stringResource(R.string.settings_camera),
        icon = Icons.Outlined.Cameraswitch,
        options = options,
        selected = s.cameraFacing,
        label = { stringResource(it.labelRes()) },
        onSelect = { f -> update { it.copy(cameraFacing = f) } },
    )
}

@Composable
private fun SeparateCameraFilesSetting(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    if (!remember { context.supportsDualCamera() }) return
    SwitchSetting(
        title = stringResource(R.string.settings_separate_camera_files),
        summary = stringResource(R.string.settings_separate_camera_files_summary),
        icon = Icons.Outlined.VideoLibrary,
        checked = s.separateCameraFiles,
        onCheckedChange = { v -> update { it.copy(separateCameraFiles = v) } },
        enabled = s.cameraFacing == CameraFacing.BOTH,
    )
}

@Composable
private fun PictureInPictureSetting(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    if (!remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }) return
    SwitchSetting(
        title = stringResource(R.string.settings_picture_in_picture),
        summary = stringResource(R.string.settings_picture_in_picture_summary),
        icon = Icons.Outlined.PictureInPictureAlt,
        checked = s.pictureInPicture,
        onCheckedChange = { v -> update { it.copy(pictureInPicture = v) } },
    )
}

/** Auto-record on navigation, plus the special accesses it needs (re-checked on resume). */
@Composable
private fun NavigationRecordingSettings(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(NavigationListenerService.isEnabled(context)) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        listenerEnabled = NavigationListenerService.isEnabled(context)
        canDrawOverlays = Settings.canDrawOverlays(context)
        onPauseOrDispose {}
    }
    val openNotificationAccess = {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            NavigationListenerService.componentName(context).flattenToString(),
        )
        runCatching { context.startActivity(detail) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Unit
    }
    Column {
        SwitchSetting(
            title = stringResource(R.string.settings_auto_record_navigation),
            summary = stringResource(R.string.settings_auto_record_navigation_summary),
            icon = Icons.Outlined.Navigation,
            checked = s.autoRecordOnNavigation,
            onCheckedChange = { v ->
                update { it.copy(autoRecordOnNavigation = v) }
                if (v && !listenerEnabled) openNotificationAccess()
            },
        )
        if (!s.autoRecordOnNavigation) return@Column
        if (!listenerEnabled) {
            ActionSetting(
                title = stringResource(R.string.settings_notification_access),
                summary = stringResource(R.string.settings_notification_access_summary),
                icon = Icons.Outlined.NotificationsActive,
                onClick = openNotificationAccess,
            )
        }
        ActionSetting(
            title = stringResource(R.string.settings_start_without_tap),
            summary = stringResource(
                if (canDrawOverlays) R.string.settings_start_without_tap_on else R.string.settings_start_without_tap_off,
            ),
            icon = Icons.Outlined.Layers,
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                }
            },
        )
        ChoiceSetting(
            title = stringResource(R.string.settings_stop_when_navigation_ends),
            icon = Icons.Outlined.StopCircle,
            options = NavigationStopDelay.entries,
            selected = s.navigationStopDelay,
            label = { stringResource(it.labelRes()) },
            onSelect = { d -> update { it.copy(navigationStopDelay = d) } },
        )
    }
}

@Composable
private fun BatteryOptimizationSetting() {
    val context = LocalContext.current
    ActionSetting(
        title = stringResource(R.string.settings_battery),
        summary = stringResource(R.string.settings_battery_summary),
        icon = Icons.Outlined.BatteryChargingFull,
        onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        },
    )
}

@Composable
private fun VersionSetting() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    ActionSetting(
        title = stringResource(R.string.settings_version),
        summary = version,
        icon = Icons.Outlined.Info,
    )
}

private fun VideoQuality.labelRes() = when (this) {
    VideoQuality.SD -> R.string.quality_sd
    VideoQuality.HD -> R.string.quality_hd
    VideoQuality.FHD -> R.string.quality_fhd
    VideoQuality.UHD -> R.string.quality_uhd
}

private fun CameraFacing.labelRes() = when (this) {
    CameraFacing.BACK -> R.string.camera_back
    CameraFacing.FRONT -> R.string.camera_front
    CameraFacing.BOTH -> R.string.camera_both
}

private fun VideoAspectRatio.labelRes() = when (this) {
    VideoAspectRatio.RATIO_16_9 -> R.string.aspect_16_9
    VideoAspectRatio.RATIO_4_3 -> R.string.aspect_4_3
}

private fun NavigationStopDelay.labelRes() = when (this) {
    NavigationStopDelay.NEVER -> R.string.navigation_stop_never
    NavigationStopDelay.IMMEDIATELY -> R.string.navigation_stop_immediately
    NavigationStopDelay.ONE_MINUTE -> R.string.navigation_stop_one_minute
    NavigationStopDelay.TWO_MINUTES -> R.string.navigation_stop_two_minutes
    NavigationStopDelay.FIVE_MINUTES -> R.string.navigation_stop_five_minutes
}

private fun VideoBitrate.labelRes() = when (this) {
    VideoBitrate.AUTO -> R.string.bitrate_auto
    VideoBitrate.LOW -> R.string.bitrate_low
    VideoBitrate.HIGH -> R.string.bitrate_high
    VideoBitrate.MAXIMUM -> R.string.bitrate_maximum
}

private fun GSensorSensitivity.labelRes() = when (this) {
    GSensorSensitivity.VERY_LOW -> R.string.sensitivity_very_low
    GSensorSensitivity.LOW -> R.string.sensitivity_low
    GSensorSensitivity.NORMAL -> R.string.sensitivity_normal
    GSensorSensitivity.HIGH -> R.string.sensitivity_high
    GSensorSensitivity.VERY_HIGH -> R.string.sensitivity_very_high
}

private fun ThemeMode.labelRes() = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private class SettingsPreviewProvider : CollectionPreviewParameterProvider<AppSettings>(
    listOf(
        AppSettings(),
        AppSettings(videoOverlay = false, gSensorEnabled = false, autoRecordOnNavigation = true, pinHash = "preview"),
    ),
)

@PreviewLightDark
@Composable
private fun SettingsContentPreview(@PreviewParameter(SettingsPreviewProvider::class) settings: AppSettings) {
    DashCamPreviewTheme {
        SettingsContent(settings = settings, onBack = {}, onOpenEventLog = {}, onUpdate = {}, onChangePin = { _, _ -> true })
    }
}
