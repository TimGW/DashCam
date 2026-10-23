package com.dashcam.presentation.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.dashcam.domain.model.RecordingType
import com.dashcam.presentation.player.PhotoScreen
import com.dashcam.presentation.player.PlayerScreen
import com.dashcam.presentation.record.RecordScreen
import com.dashcam.presentation.recordings.RecordingsScreen
import com.dashcam.presentation.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object RecordDestination

@Serializable
data object RecordingsDestination

@Serializable
data object SettingsDestination

@Serializable
data class PlayerDestination(val path: String)

@Serializable
data class PhotoDestination(val path: String)

@Composable
fun DashCamNavHost(
    startRecordingRequest: Boolean,
    onStartRecordingHandled: () -> Unit,
    minimizeAfterStart: Boolean,
    onMinimized: (enteringPictureInPicture: Boolean) -> Unit,
) {
    val navController = rememberNavController()
    // Recording starts from the record screen, so a start request (tile, navigation) returns there.
    LaunchedEffect(startRecordingRequest) {
        if (startRecordingRequest) navController.popBackStack(RecordDestination, inclusive = false)
    }
    NavHost(
        navController = navController,
        startDestination = RecordDestination,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable<RecordDestination> {
            RecordScreen(
                onOpenRecordings = { navController.navigate(RecordingsDestination) },
                onOpenSettings = { navController.navigate(SettingsDestination) },
                startRecordingRequest = startRecordingRequest,
                onStartRecordingHandled = onStartRecordingHandled,
                minimizeAfterStart = minimizeAfterStart,
                onMinimized = onMinimized,
            )
        }
        composable<RecordingsDestination> {
            RecordingsScreen(
                onBack = { navController.navigateUp() },
                onOpen = { recording ->
                    navController.navigate(
                        when (recording.type) {
                            RecordingType.VIDEO -> PlayerDestination(recording.id)
                            RecordingType.PHOTO -> PhotoDestination(recording.id)
                        },
                    )
                },
            )
        }
        composable<SettingsDestination> {
            SettingsScreen(onBack = { navController.navigateUp() })
        }
        composable<PlayerDestination> { entry ->
            PlayerScreen(path = entry.toRoute<PlayerDestination>().path, onBack = { navController.navigateUp() })
        }
        composable<PhotoDestination> { entry ->
            PhotoScreen(path = entry.toRoute<PhotoDestination>().path, onBack = { navController.navigateUp() })
        }
    }
}
