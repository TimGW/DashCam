package com.dashcam.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dashcam.R
import com.dashcam.domain.usecase.ManagePinUseCase
import kotlinx.coroutines.launch

enum class PinDialogMode { SET, CHANGE, REMOVE }

@Composable
fun PinDialog(
    mode: PinDialogMode,
    onSubmit: suspend (currentPin: String?, newPin: String?) -> Boolean,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val needsCurrent = mode != PinDialogMode.SET
    val needsNew = mode != PinDialogMode.REMOVE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (mode) {
                        PinDialogMode.SET -> R.string.settings_pin_set
                        PinDialogMode.CHANGE -> R.string.settings_pin_change
                        PinDialogMode.REMOVE -> R.string.settings_pin_remove
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == PinDialogMode.SET) {
                    Text(stringResource(R.string.pin_forget_warning), style = MaterialTheme.typography.bodySmall)
                }
                if (needsCurrent) PinField(current, { current = it }, R.string.pin_current)
                if (needsNew) {
                    PinField(newPin, { newPin = it }, R.string.pin_new)
                    PinField(confirm, { confirm = it }, R.string.pin_confirm)
                }
                errorRes?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                errorRes = when {
                    needsNew && newPin.length !in ManagePinUseCase.PIN_LENGTH_RANGE -> R.string.pin_length_error
                    needsNew && newPin != confirm -> R.string.pin_mismatch
                    else -> null
                }
                if (errorRes != null) return@TextButton
                scope.launch {
                    val ok = onSubmit(current.takeIf { needsCurrent }, newPin.takeIf { needsNew })
                    if (ok) onDismiss() else errorRes = R.string.pin_incorrect
                }
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, labelRes: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter(Char::isDigit).take(ManagePinUseCase.PIN_LENGTH_RANGE.last)) },
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
