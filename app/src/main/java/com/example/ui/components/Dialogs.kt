package com.example.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.R

@Composable
fun GoToPageDialog(
    currentPage: Int,
    totalPages: Int,
    onGo: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf(currentPage.toString()) }
    val page = input.toIntOrNull()
    val valid = page != null && page in 1..totalPages
    val submit = { if (valid) { onGo(page!!); onDismiss() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_page_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { value -> input = value.filter { it.isDigit() }.take(5) },
                singleLine = true,
                isError = input.isNotEmpty() && !valid,
                label = { Text(stringResource(R.string.go_to_page_hint, totalPages)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() })
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = valid) {
                Text(stringResource(R.string.btn_go), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Avertissement avant de lire à voix haute alors que le téléphone est en silencieux. */
@Composable
fun SpeakerWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
        title = { Text(stringResource(R.string.speaker_warning_title)) },
        text = { Text(stringResource(R.string.speaker_warning_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.btn_read_anyway), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
