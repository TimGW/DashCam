package com.dashcam.presentation.recordings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.datasource.CollectionPreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dashcam.R
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.domain.model.SortOrder
import com.dashcam.domain.model.StorageStats
import com.dashcam.presentation.common.PreviewData
import com.dashcam.presentation.common.RecordingThumbnail
import com.dashcam.presentation.common.containerViewModelFactory
import com.dashcam.presentation.common.formatBytes
import com.dashcam.presentation.common.formatDateTime
import com.dashcam.presentation.common.formatDuration
import com.dashcam.presentation.common.shareRecording
import com.dashcam.presentation.pin.PinLockScreen
import com.dashcam.presentation.theme.DashCamPreviewTheme

@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onOpen: (Recording) -> Unit,
    viewModel: RecordingsViewModel = viewModel(
        factory = containerViewModelFactory {
            RecordingsViewModel(
                observeRecordings = it.observeRecordings,
                setRecordingsLocked = it.setRecordingsLocked,
                deleteRecordings = it.deleteRecordings,
                exporter = it.recordingExporter,
                managePin = it.managePin,
                pinSession = it.pinSession,
                settingsRepository = it.settingsRepository,
                recordingsRepository = it.recordingsRepository,
            )
        },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.pinRequired) {
        PinLockScreen(onBack = onBack, onSubmit = viewModel::unlock)
        return
    }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                RecordingsMessage.SavedToGallery -> context.getString(R.string.msg_saved_to_gallery)
                RecordingsMessage.SaveFailed -> context.getString(R.string.msg_save_failed)
                is RecordingsMessage.Deleted ->
                    context.resources.getQuantityString(R.plurals.msg_deleted, message.count, message.count)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    RecordingsContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpen = onOpen,
        onSelectType = viewModel::selectType,
        onSelectSort = viewModel::selectSort,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onSetLocked = viewModel::setLocked,
        onShare = { context.shareRecording(it) },
        onSaveToGallery = viewModel::saveToGallery,
        onDelete = viewModel::delete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingsContent(
    state: RecordingsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpen: (Recording) -> Unit,
    onSelectType: (RecordingType) -> Unit,
    onSelectSort: (SortOrder) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSetLocked: (ids: Collection<String>, locked: Boolean) -> Unit,
    onShare: (Recording) -> Unit,
    onSaveToGallery: (Recording) -> Unit,
    onDelete: (ids: Collection<String>) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Collection<String>?>(null) }

    val selecting = state.selection.isNotEmpty()
    val onItemClick: (Recording) -> Unit = { recording ->
        if (selecting) onToggleSelection(recording.id) else onOpen(recording)
    }
    val itemActions = RecordingActions(
        onLock = { onSetLocked(listOf(it.id), !it.isLocked) },
        onShare = onShare,
        onSave = onSaveToGallery,
        onDelete = { pendingDelete = listOf(it.id) },
    )

    Scaffold(
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = state.selection.size,
                    onClear = onClearSelection,
                    onLock = { onSetLocked(state.selection, true) },
                    onUnlock = { onSetLocked(state.selection, false) },
                    onDelete = { pendingDelete = state.selection },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.recordings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    },
                    actions = { SortMenu(selected = state.sort, onSelect = onSelectSort) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = state.type.ordinal) {
                Tab(
                    selected = state.type == RecordingType.VIDEO,
                    onClick = { onSelectType(RecordingType.VIDEO) },
                    text = { Text(stringResource(R.string.recordings_tab_videos)) },
                )
                Tab(
                    selected = state.type == RecordingType.PHOTO,
                    onClick = { onSelectType(RecordingType.PHOTO) },
                    text = { Text(stringResource(R.string.recordings_tab_photos)) },
                )
            }
            state.stats?.let { StorageSummary(it, state.maxStorageGb) }
            when {
                state.isLoading -> Unit
                state.items.isEmpty() -> EmptyState(state.type)
                state.type == RecordingType.VIDEO -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.items.groupCameraPairs(), key = { it.first().id }) { group ->
                        val row: @Composable (Recording) -> Unit = { recording ->
                            VideoRow(
                                recording = recording,
                                selected = recording.id in state.selection,
                                onClick = { onItemClick(recording) },
                                onLongClick = { onToggleSelection(recording.id) },
                                actions = itemActions,
                                grouped = group.size > 1,
                            )
                        }
                        if (group.size == 1) row(group.single()) else CameraPairGroup { group.forEach { row(it) } }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { recording ->
                        PhotoTile(
                            recording = recording,
                            selected = recording.id in state.selection,
                            onClick = { onItemClick(recording) },
                            onLongClick = { onToggleSelection(recording.id) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(pluralStringResource(R.plurals.delete_title, ids.size, ids.size)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(ids)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private data class RecordingActions(
    val onLock: (Recording) -> Unit,
    val onShare: (Recording) -> Unit,
    val onSave: (Recording) -> Unit,
    val onDelete: (Recording) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    recording: Recording,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    actions: RecordingActions,
    /** Shown inside a [CameraPairGroup], which provides the background. */
    grouped: Boolean = false,
) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = when {
            selected -> ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            grouped -> ListItemDefaults.colors(containerColor = Color.Transparent)
            else -> ListItemDefaults.colors()
        },
        leadingContent = {
            Box {
                RecordingThumbnail(
                    recording = recording,
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                recording.durationMillis?.let {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    ) {
                        Text(
                            formatDuration(it),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                    )
                }
            }
        },
        headlineContent = { Text(formatDateTime(recording.createdAtMillis)) },
        supportingContent = {
            val parts = listOfNotNull(
                formatBytes(context, recording.sizeBytes),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(parts.joinToString(" · "))
                when {
                    recording.isFrontCamera -> CameraTag(stringResource(R.string.recording_tag_front_camera))
                    recording.pairedWith != null -> CameraTag(stringResource(R.string.recording_tag_back_camera))
                }
            }
        },
        trailingContent = {
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more_actions))
                    }
                    if (recording.isLocked) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.cd_locked),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                RecordingMenu(
                    expanded = menuOpen,
                    recording = recording,
                    actions = actions,
                    onDismiss = { menuOpen = false },
                )
            }
        },
    )
}

/** Splits the list into single videos and both-cameras pairs, which sorting keeps side by side. */
private fun List<Recording>.groupCameraPairs(): List<List<Recording>> {
    val groups = mutableListOf<List<Recording>>()
    var i = 0
    while (i < size) {
        val next = getOrNull(i + 1)
        if (next != null && this[i].pairedWith == next.id) {
            groups += listOf(this[i], next)
            i += 2
        } else {
            groups += listOf(this[i])
            i++
        }
    }
    return groups
}

/** Frames the two videos of a both-cameras session, so it's clear they were recorded together. */
@Composable
private fun CameraPairGroup(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.recording_group_both_cameras),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            content()
        }
    }
}

/** Says which camera recorded a video that has a partner from the other camera. */
@Composable
private fun CameraTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RecordingMenu(
    expanded: Boolean,
    recording: Recording,
    actions: RecordingActions,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (recording.isLocked) R.string.action_unlock else R.string.action_lock)) },
            leadingIcon = { Icon(if (recording.isLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null) },
            onClick = { onDismiss(); actions.onLock(recording) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Outlined.Share, null) },
            onClick = { onDismiss(); actions.onShare(recording) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_save_to_gallery)) },
            leadingIcon = { Icon(Icons.Outlined.Download, null) },
            onClick = { onDismiss(); actions.onSave(recording) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
            onClick = { onDismiss(); actions.onDelete(recording) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(recording: Recording, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        RecordingThumbnail(recording, Modifier.fillMaxSize())
        if (selected) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), modifier = Modifier.fillMaxSize()) {}
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.selection_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_selection)) }
        },
        actions = {
            IconButton(onClick = onLock) { Icon(Icons.Outlined.Lock, stringResource(R.string.action_lock)) }
            IconButton(onClick = onUnlock) { Icon(Icons.Outlined.LockOpen, stringResource(R.string.action_unlock)) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
private fun SortMenu(selected: SortOrder, onSelect: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.cd_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(order.labelRes())) },
                    leadingIcon = { RadioButton(selected = order == selected, onClick = null) },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageSummary(stats: StorageStats, maxStorageGb: Int) {
    val context = LocalContext.current
    val limitBytes = if (maxStorageGb == 0) null else maxStorageGb * 1_000_000_000L
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (limitBytes != null) {
                stringResource(
                    R.string.storage_used_of_limit,
                    formatBytes(context, stats.recordingsBytes),
                    formatBytes(context, limitBytes),
                )
            } else {
                stringResource(R.string.storage_used, formatBytes(context, stats.recordingsBytes))
            },
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(
            progress = {
                val total = limitBytes ?: (stats.recordingsBytes + stats.freeBytes).coerceAtLeast(1)
                (stats.recordingsBytes.toFloat() / total).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.storage_free, formatBytes(context, stats.freeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(type: RecordingType) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (type == RecordingType.VIDEO) R.string.recordings_empty_videos else R.string.recordings_empty_photos,
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

private fun SortOrder.labelRes(): Int = when (this) {
    SortOrder.NEWEST -> R.string.sort_newest
    SortOrder.OLDEST -> R.string.sort_oldest
    SortOrder.NAME -> R.string.sort_name
    SortOrder.LARGEST -> R.string.sort_largest
    SortOrder.LONGEST -> R.string.sort_longest
}

private class RecordingsPreviewProvider : CollectionPreviewParameterProvider<RecordingsUiState>(
    listOf(
        RecordingsUiState(
            items = PreviewData.videos,
            isLoading = false,
            stats = PreviewData.storageStats,
            maxStorageGb = 8,
        ),
        RecordingsUiState(
            items = PreviewData.videos,
            isLoading = false,
            selection = setOf(PreviewData.frontCameraVideo.id, PreviewData.lockedVideo.id),
            stats = PreviewData.storageStats,
            maxStorageGb = 8,
        ),
        RecordingsUiState(
            type = RecordingType.PHOTO,
            items = PreviewData.photos,
            isLoading = false,
            selection = setOf(PreviewData.photos.first().id),
            stats = PreviewData.storageStats,
        ),
        RecordingsUiState(isLoading = false, stats = PreviewData.storageStats.copy(recordingsBytes = 0)),
    ),
)

@PreviewLightDark
@Composable
private fun RecordingsContentPreview(@PreviewParameter(RecordingsPreviewProvider::class) state: RecordingsUiState) {
    DashCamPreviewTheme {
        RecordingsContent(
            state = state,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onOpen = {},
            onSelectType = {},
            onSelectSort = {},
            onToggleSelection = {},
            onClearSelection = {},
            onSetLocked = { _, _ -> },
            onShare = {},
            onSaveToGallery = {},
            onDelete = {},
        )
    }
}

@Preview
@Composable
private fun RecordingMenuPreview() {
    DashCamPreviewTheme {
        RecordingMenu(
            expanded = true,
            recording = PreviewData.lockedVideo,
            actions = RecordingActions(onLock = {}, onShare = {}, onSave = {}, onDelete = {}),
            onDismiss = {},
        )
    }
}
