package com.dashcam.presentation.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CarCrash
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dashcam.R
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.CameraFacing
import com.dashcam.domain.model.GSensorSensitivity
import com.dashcam.domain.model.SpeedUnit
import com.dashcam.domain.model.ThemeMode
import com.dashcam.domain.model.VideoQuality
import com.dashcam.presentation.common.containerViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = containerViewModelFactory { SettingsViewModel(it.settingsRepository, it.managePin) },
    ),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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
            settingsContent(current, viewModel::update, onPinAction = { pinDialog = it })
        }
    }

    pinDialog?.let { mode ->
        PinDialog(mode = mode, onSubmit = viewModel::changePin, onDismiss = { pinDialog = null })
    }
}

private fun LazyListScope.settingsContent(
    s: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onPinAction: (PinDialogMode) -> Unit,
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
    item {
        ChoiceSetting(
            title = stringResource(R.string.settings_camera),
            icon = Icons.Outlined.Cameraswitch,
            options = CameraFacing.entries,
            selected = s.cameraFacing,
            label = { stringResource(if (it == CameraFacing.BACK) R.string.camera_back else R.string.camera_front) },
            onSelect = { f -> update { it.copy(cameraFacing = f) } },
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
            label = { if (it == 0) stringResource(R.string.storage_unlimited) else stringResource(R.string.storage_gb, it) },
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

    item { SectionHeader(stringResource(R.string.settings_section_location)) }
    item {
        SwitchSetting(
            title = stringResource(R.string.settings_subtitles),
            summary = stringResource(R.string.settings_subtitles_summary),
            icon = Icons.Outlined.Subtitles,
            checked = s.writeSubtitles,
            onCheckedChange = { v -> update { it.copy(writeSubtitles = v) } },
        )
    }
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
    item { VersionSetting() }
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
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
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
