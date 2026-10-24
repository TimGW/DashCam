package com.dashcam.presentation.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.datasource.CollectionPreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dashcam.R
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.CameraState
import com.dashcam.domain.model.LocationSample
import com.dashcam.domain.model.RecorderEvent
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.model.SpeedUnit
import com.dashcam.domain.model.VideoAspectRatio
import com.dashcam.domain.model.toSpeed
import com.dashcam.presentation.common.containerViewModelFactory
import com.dashcam.presentation.common.formatBytes
import com.dashcam.presentation.common.formatDuration
import com.dashcam.presentation.theme.DashCamPreviewTheme
import com.dashcam.presentation.theme.GpsGreen
import com.dashcam.presentation.theme.RecordRed
import kotlinx.coroutines.delay

private val RecordPermissions = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private const val GPS_STALE_MS = 5_000L

@Composable
fun RecordScreen(
    onOpenRecordings: () -> Unit,
    onOpenSettings: () -> Unit,
    startRecordingRequest: Boolean,
    onStartRecordingHandled: () -> Unit,
    minimizeAfterStart: Boolean,
    /** Called once out of the way; true when entering picture-in-picture rather than the background. */
    onMinimized: (enteringPictureInPicture: Boolean) -> Unit,
    viewModel: RecordViewModel = viewModel(
        factory = containerViewModelFactory {
            RecordViewModel(
                recorder = it.recorderController,
                camera = it.cameraController,
                preview = it.cameraEngine,
                settingsRepository = it.settingsRepository,
                locationRepository = it.locationRepository,
                recordingsRepository = it.recordingsRepository,
            )
        },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var hasCamera by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var permissionsRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasCamera = context.isGranted(Manifest.permission.CAMERA)
        permissionsRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(RecordPermissions)
    }
    LaunchedEffect(hasCamera) {
        viewModel.onCameraPermission(hasCamera)
    }
    LaunchedEffect(hasCamera, startRecordingRequest) {
        if (hasCamera) {
            viewModel.maybeAutoStart(startRecordingRequest)
            if (startRecordingRequest) onStartRecordingHandled()
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.setPreviewVisible(true)
        onPauseOrDispose { viewModel.setPreviewVisible(false) }
    }

    val view = LocalView.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        view.display?.rotation?.let(viewModel::setTargetRotation)
    }
    val keepScreenOn = state.settings?.keepScreenOn == true
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = event.messageRes()?.let(context::getString) ?: return@collect
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val inPictureInPicture = rememberIsInPictureInPictureMode()
    val recorder = state.recorder
    PictureInPictureEffect(
        enabled = state.settings?.pictureInPicture == true && recorder is RecorderState.Recording,
        aspectRatio = pictureInPictureAspect(state.settings?.aspectRatio, configuration.orientation),
        isLocked = (recorder as? RecorderState.Recording)?.isLocked == true,
    )
    // Stopping from the window's own button leaves nothing to show: close the window.
    val activity = LocalActivity.current
    // Started for another app (navigation): once recording, hand the screen back to it.
    LaunchedEffect(minimizeAfterStart, state.isRecording) {
        val componentActivity = activity as? ComponentActivity ?: return@LaunchedEffect
        if (!minimizeAfterStart || !state.isRecording) return@LaunchedEffect
        val inPip = state.settings?.pictureInPicture == true && enterPictureInPicture(
            componentActivity,
            pictureInPictureAspect(state.settings?.aspectRatio, configuration.orientation),
            isLocked = (recorder as? RecorderState.Recording)?.isLocked == true,
        )
        if (!inPip) componentActivity.moveTaskToBack(true)
        onMinimized(inPip)
    }
    LaunchedEffect(inPictureInPicture, state.isRecording) {
        if (inPictureInPicture && !state.isRecording) activity?.moveTaskToBack(false)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasCamera) {
            CameraPreview(
                onSurfaceProvider = viewModel::attachPreview,
                onZoom = viewModel::zoom,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (permissionsRequested) {
            PermissionPrompt(
                onGrant = { permissionLauncher.launch(RecordPermissions) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // The PiP window only shows the viewfinder; its controls are the window's own actions.
        if (!inPictureInPicture) Column(Modifier.fillMaxSize()) {
            TopOverlay(
                state = state,
                hasLocationPermission = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
                onSwitchCamera = viewModel::switchCamera,
                onOpenRecordings = onOpenRecordings,
                onOpenSettings = onOpenSettings,
            )
            if (state.camera.hasSecondaryPreview) {
                val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                SecondaryCameraPreview(
                    onSurfaceProvider = viewModel::attachSecondaryPreview,
                    modifier = Modifier
                        .align(Alignment.End)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(end = 12.dp)
                        .width(if (landscape) 160.dp else 96.dp)
                        .aspectRatio(if (landscape) 16f / 9f else 9f / 16f)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            Spacer(Modifier.weight(1f))
            BottomOverlay(
                state = state,
                snackbarHostState = snackbarHostState,
                enabled = hasCamera,
                onToggleRecording = viewModel::toggleRecording,
                onToggleAudio = viewModel::toggleAudio,
                onToggleTorch = viewModel::toggleTorch,
                onTakePhoto = viewModel::takePhoto,
                onToggleLock = viewModel::toggleLock,
            )
        }
    }
}

/** The recording's shape in the current orientation, as the PiP window's aspect ratio. */
private fun pictureInPictureAspect(aspectRatio: VideoAspectRatio?, orientation: Int): Rational {
    val (long, short) = if (aspectRatio == VideoAspectRatio.RATIO_4_3) 4 to 3 else 16 to 9
    return if (orientation == Configuration.ORIENTATION_LANDSCAPE) Rational(long, short) else Rational(short, long)
}

@Composable
private fun CameraPreview(
    onSurfaceProvider: (androidx.camera.core.Preview.SurfaceProvider?) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                // Show the whole frame: filling the screen would hide parts of the recording.
                scaleType = PreviewView.ScaleType.FIT_CENTER
                onSurfaceProvider(surfaceProvider)
            }
        },
        onRelease = { onSurfaceProvider(null) },
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom) }
        },
    )
}

/** Front camera viewfinder while both cameras record to separate files. */
@Composable
private fun SecondaryCameraPreview(
    onSurfaceProvider: (androidx.camera.core.Preview.SurfaceProvider?) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                onSurfaceProvider(surfaceProvider)
            }
        },
        onRelease = { onSurfaceProvider(null) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopOverlay(
    state: RecordUiState,
    hasLocationPermission: Boolean,
    onSwitchCamera: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPills(state, hasLocationPermission)
        }
        OverlayIconButton(
            icon = Icons.Filled.Cameraswitch,
            contentDescription = stringResource(R.string.cd_switch_camera),
            onClick = onSwitchCamera,
        )
        OverlayIconButton(
            icon = Icons.Filled.VideoLibrary,
            contentDescription = stringResource(R.string.cd_recordings),
            onClick = onOpenRecordings,
        )
        OverlayIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.cd_settings),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun StatusPills(state: RecordUiState, hasLocationPermission: Boolean) {
    val context = LocalContext.current
    val recorder = state.recorder
    if (recorder is RecorderState.Recording) {
        val elapsed = rememberElapsed(recorder.startedAtMillis)
        StatusPill(
            icon = if (recorder.isLocked) Icons.Filled.Lock else null,
            text = if (recorder.isInterrupted) {
                stringResource(R.string.record_status_retrying)
            } else {
                stringResource(R.string.record_status_rec, formatDuration(elapsed))
            },
            containerColor = RecordRed,
        )
    }
    val fresh = state.location?.let { System.currentTimeMillis() - it.timeMillis < GPS_STALE_MS } == true
    if (hasLocationPermission) {
        StatusPill(
            icon = if (fresh) Icons.Filled.GpsFixed else Icons.Filled.GpsOff,
            text = stringResource(if (fresh) R.string.record_status_gps else R.string.record_status_no_gps),
            iconTint = if (fresh) GpsGreen else Color.White,
        )
    }
    state.freeBytes?.let {
        StatusPill(icon = Icons.Filled.SdStorage, text = formatBytes(context, it))
    }
}

@Composable
private fun BottomOverlay(
    state: RecordUiState,
    snackbarHostState: SnackbarHostState,
    enabled: Boolean,
    onToggleRecording: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleTorch: () -> Unit,
    onTakePhoto: () -> Unit,
    onToggleLock: () -> Unit,
) {
    val recorder = state.recorder as? RecorderState.Recording
    val audioOn = recorder?.let { !it.isAudioMuted } ?: (state.settings?.recordAudio != false)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SnackbarHost(snackbarHostState)
        SpeedReadout(state)
        Spacer(Modifier.size(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(
                icon = if (audioOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = stringResource(if (audioOn) R.string.cd_mute else R.string.cd_unmute),
                onClick = onToggleAudio,
                enabled = enabled,
            )
            OverlayIconButton(
                icon = if (state.camera.torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                contentDescription = stringResource(R.string.cd_flash),
                onClick = onToggleTorch,
                active = state.camera.torchOn,
                enabled = enabled && state.camera.hasFlash,
            )
            RecordButton(recording = recorder != null, enabled = enabled, onClick = onToggleRecording)
            OverlayIconButton(
                icon = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(R.string.cd_take_photo),
                onClick = onTakePhoto,
                enabled = enabled && state.camera.isReady && state.camera.photoSupported,
            )
            OverlayIconButton(
                icon = if (recorder?.isLocked == true) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                contentDescription = stringResource(
                    if (recorder?.isLocked == true) R.string.cd_unlock_clip else R.string.cd_lock_clip,
                ),
                onClick = onToggleLock,
                active = recorder?.isLocked == true,
                enabled = recorder != null,
            )
        }
    }
}

@Composable
private fun SpeedReadout(state: RecordUiState) {
    val unit = state.settings?.speedUnit ?: SpeedUnit.KMH
    val location = state.location?.takeIf { System.currentTimeMillis() - it.timeMillis < GPS_STALE_MS }
    val speed = location?.speedMetersPerSecond?.toSpeed(unit)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = speed?.toString() ?: "--",
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (unit == SpeedUnit.KMH) R.string.unit_kmh else R.string.unit_mph),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val innerSize by animateDpAsState(if (recording) 30.dp else 60.dp, label = "recordSize")
    val corner by animateDpAsState(if (recording) 8.dp else 30.dp, label = "recordCorner")
    val description = stringResource(if (recording) R.string.cd_stop_recording else R.string.cd_start_recording)
    Box(
        modifier = Modifier
            .size(80.dp)
            .border(4.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(corner))
                .background(RecordRed.copy(alpha = if (enabled) 1f else 0.4f)),
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            disabledContainerColor = Color.Black.copy(alpha = 0.25f),
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

@Composable
private fun StatusPill(
    icon: ImageVector?,
    text: String,
    containerColor: Color = Color.Black.copy(alpha = 0.45f),
    iconTint: Color = Color.White,
) {
    Surface(shape = CircleShape, color = containerColor, contentColor = Color.White) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        Text(
            text = stringResource(R.string.permission_camera_title),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.permission_camera_body),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
    }
}

@Composable
private fun rememberElapsed(startedAtMillis: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    return (now - startedAtMillis).coerceAtLeast(0)
}

private fun RecorderEvent.messageRes(): Int? = when (this) {
    RecorderEvent.Started, RecorderEvent.Stopped -> null
    is RecorderEvent.LockChanged -> if (locked) R.string.msg_clip_locked else R.string.msg_clip_unlocked
    RecorderEvent.ImpactDetected -> R.string.msg_impact_detected
    RecorderEvent.EventMarked -> R.string.msg_event_marked
    RecorderEvent.EventClipSaved -> R.string.msg_event_clip_saved
    RecorderEvent.PhotoSaved -> R.string.msg_photo_saved
    RecorderEvent.PhotoFailed -> R.string.msg_photo_failed
    RecorderEvent.StorageFull -> R.string.msg_storage_full
    RecorderEvent.StopToSwitchCamera -> R.string.msg_stop_to_switch_camera
    RecorderEvent.CameraInterrupted -> R.string.msg_camera_interrupted
    RecorderEvent.CameraResumed -> R.string.msg_camera_resumed
    RecorderEvent.Overheating -> R.string.msg_overheating
    RecorderEvent.StoppedTooHot -> R.string.msg_stopped_too_hot
    RecorderEvent.BatteryLow -> R.string.msg_battery_low
    RecorderEvent.StoppedBatteryEmpty -> R.string.msg_stopped_battery_empty
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private val previewRecording = RecorderState.Recording(
    startedAtMillis = System.currentTimeMillis() - 754_000,
    segmentStartedAtMillis = System.currentTimeMillis() - 34_000,
    isLocked = true,
    isAudioMuted = false,
)

private class RecordUiStatePreviewProvider : CollectionPreviewParameterProvider<RecordUiState>(
    listOf(
        RecordUiState(
            camera = CameraState(isReady = true, hasFlash = true),
            settings = AppSettings(),
            freeBytes = 41_000_000_000,
        ),
        RecordUiState(
            recorder = previewRecording,
            camera = CameraState(isReady = true, hasFlash = true, torchOn = true),
            location = LocationSample(
                latitude = 52.37,
                longitude = 4.89,
                altitudeMeters = null,
                speedMetersPerSecond = 23.6f,
                accuracyMeters = 4f,
                timeMillis = System.currentTimeMillis(),
            ),
            settings = AppSettings(),
            freeBytes = 41_000_000_000,
        ),
        RecordUiState(
            recorder = previewRecording.copy(isLocked = false, isAudioMuted = true, isInterrupted = true),
            settings = AppSettings(speedUnit = SpeedUnit.MPH),
            freeBytes = 1_200_000_000,
        ),
    ),
)

/** The record screen's controls over a black stand-in for the camera viewfinder. */
@Preview(showSystemUi = true)
@Composable
private fun RecordOverlaysPreview(@PreviewParameter(RecordUiStatePreviewProvider::class) state: RecordUiState) {
    DashCamPreviewTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            TopOverlay(
                state = state,
                hasLocationPermission = true,
                onSwitchCamera = {},
                onOpenRecordings = {},
                onOpenSettings = {},
            )
            Spacer(Modifier.weight(1f))
            BottomOverlay(
                state = state,
                snackbarHostState = remember { SnackbarHostState() },
                enabled = true,
                onToggleRecording = {},
                onToggleAudio = {},
                onToggleTorch = {},
                onTakePhoto = {},
                onToggleLock = {},
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PermissionPromptPreview() {
    DashCamPreviewTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            PermissionPrompt(onGrant = {}, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun RecordControlsPreview() {
    DashCamPreviewTheme {
        Row(
            Modifier
                .background(Color.Black)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordButton(recording = false, enabled = true, onClick = {})
            RecordButton(recording = true, enabled = true, onClick = {})
            RecordButton(recording = false, enabled = false, onClick = {})
            OverlayIconButton(icon = Icons.Filled.Mic, contentDescription = "", onClick = {})
            OverlayIconButton(icon = Icons.Filled.Lock, contentDescription = "", onClick = {}, active = true)
            OverlayIconButton(icon = Icons.Filled.PhotoCamera, contentDescription = "", onClick = {}, enabled = false)
        }
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun StatusPillPreview() {
    DashCamPreviewTheme {
        Row(
            Modifier
                .background(Color.Black)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(icon = Icons.Filled.Lock, text = "REC 12:34", containerColor = RecordRed)
            StatusPill(icon = Icons.Filled.GpsFixed, text = "GPS", iconTint = GpsGreen)
            StatusPill(icon = Icons.Filled.SdStorage, text = "41 GB")
        }
    }
}
