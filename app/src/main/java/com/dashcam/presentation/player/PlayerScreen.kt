package com.dashcam.presentation.player

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.dashcam.R
import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import com.dashcam.presentation.common.loadThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Plays a recording; the telemetry .srt is attached so speed/location show as subtitles. */
@Composable
fun PlayerScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(mediaItemFor(File(path)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 0.7f)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        BackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}

@Composable
fun PhotoScreen(path: String, onBack: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            loadThumbnail(photoRecording(path), Size(2048, 2048))
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator()
        }
        BackButton(onBack, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onBack,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
        ),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
    }
}

private fun mediaItemFor(video: File): MediaItem {
    val subtitles = File(video.parentFile, "${video.nameWithoutExtension}.srt")
    return MediaItem.Builder()
        .setUri(Uri.fromFile(video))
        .apply {
            if (subtitles.exists()) {
                setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitles))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build(),
                    ),
                )
            }
        }
        .build()
}

private fun photoRecording(path: String) = Recording(
    id = path,
    name = File(path).name,
    type = RecordingType.PHOTO,
    createdAtMillis = 0,
    sizeBytes = 0,
    durationMillis = null,
    isLocked = false,
    subtitlesPath = null,
)
