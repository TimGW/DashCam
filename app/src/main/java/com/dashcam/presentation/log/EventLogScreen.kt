package com.dashcam.presentation.log

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dashcam.DashCamApplication
import com.dashcam.R
import com.dashcam.presentation.theme.DashCamPreviewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows the recording event log (newest first), so problems on the road can be traced later. */
@Composable
fun EventLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val eventLog = remember { (context.applicationContext as DashCamApplication).container.eventLog }
    var version by remember { mutableIntStateOf(0) }
    val lines by produceState(emptyList<String>(), version) {
        value = withContext(Dispatchers.IO) { eventLog.read() }
    }
    EventLogContent(
        lines = lines,
        onBack = onBack,
        onShare = {
            if (!eventLog.file.exists()) return@EventLogContent
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", eventLog.file)
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(share, null))
        },
        onClear = {
            eventLog.clear()
            version++
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventLogContent(lines: List<String>, onBack: () -> Unit, onShare: () -> Unit, onClear: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.action_share))
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.event_log_clear))
                    }
                },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.event_log_empty),
                modifier = Modifier.padding(padding).padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun EventLogContentPreview() {
    DashCamPreviewTheme {
        EventLogContent(
            lines = listOf(
                "2025-10-24 10:12:40  Recording ended: stopped by user",
                "2025-10-24 10:04:12  Impact detected: clip locked, saving a 30 s event clip",
                "2025-10-24 10:02:31  Clip locked",
                "2025-10-24 10:00:00  Recording started: back camera, 1080p 30 fps, 3 min clips",
            ),
            onBack = {},
            onShare = {},
            onClear = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EventLogEmptyPreview() {
    DashCamPreviewTheme {
        EventLogContent(lines = emptyList(), onBack = {}, onShare = {}, onClear = {})
    }
}
