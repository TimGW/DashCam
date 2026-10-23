package com.dashcam.presentation.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dashcam.R
import com.dashcam.domain.usecase.ManagePinUseCase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinLockScreen(onBack: () -> Unit, onSubmit: suspend (String) -> Boolean) {
    var pin by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val maxLength = ManagePinUseCase.PIN_LENGTH_RANGE.last
    val minLength = ManagePinUseCase.PIN_LENGTH_RANGE.first

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pin_lock_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(
                text = stringResource(if (error) R.string.pin_incorrect else R.string.pin_enter),
                style = MaterialTheme.typography.titleMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            PinDots(length = pin.length, slots = maxOf(minLength, pin.length))
            Keypad(
                onDigit = { digit ->
                    if (pin.length < maxLength) pin += digit
                    error = false
                },
                onBackspace = { pin = pin.dropLast(1) },
                confirmEnabled = pin.length >= minLength,
                onConfirm = {
                    scope.launch {
                        if (!onSubmit(pin)) {
                            error = true
                            pin = ""
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun PinDots(length: Int, slots: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(slots) { index ->
            Box(
                Modifier
                    .size(14.dp)
                    .background(
                        color = if (index < length) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
) {
    val keySize = Modifier.size(72.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit ->
                    FilledTonalButton(onClick = { onDigit(digit) }, modifier = keySize, shape = CircleShape) {
                        Text(digit.toString(), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(onClick = onBackspace, modifier = keySize) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.cd_backspace))
            }
            FilledTonalButton(onClick = { onDigit('0') }, modifier = keySize, shape = CircleShape) {
                Text("0", style = MaterialTheme.typography.headlineSmall)
            }
            FilledTonalIconButton(onClick = onConfirm, enabled = confirmEnabled, modifier = keySize) {
                Icon(Icons.Filled.Check, stringResource(R.string.cd_confirm))
            }
        }
    }
}
