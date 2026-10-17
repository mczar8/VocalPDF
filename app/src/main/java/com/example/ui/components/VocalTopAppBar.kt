package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/** Toutes les actions accessibles depuis la barre du haut et son menu ⋮. */
data class TopBarActions(
    val onCloseDocument: () -> Unit,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onToggleDarkMode: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onGoToPage: () -> Unit,
    val onToggleLock: () -> Unit,
    val onQuitApp: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocalTopAppBar(
    title: String,
    isDocumentOpen: Boolean,
    currentPage: Int,
    totalPages: Int,
    languageName: String?,
    isDarkTheme: Boolean,
    isLockEnabled: Boolean,
    fontSizeSp: Float,
    actions: TopBarActions,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier.testTag("vocal_top_app_bar"),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        title = {
            if (isDocumentOpen) {
                Column {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (totalPages > 0) {
                        val pageText = stringResource(R.string.page_counter, currentPage, totalPages)
                        Text(
                            text = if (languageName != null) "$pageText · $languageName" else pageText,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_vocal_pdf_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        },
        navigationIcon = {
            if (isDocumentOpen) {
                IconButton(onClick = actions.onCloseDocument, modifier = Modifier.testTag("btn_close_document")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back_home))
                }
            }
        },
        actions = {
            if (isDocumentOpen) {
                IconButton(onClick = actions.onOpenSettings, modifier = Modifier.testTag("btn_open_settings")) {
                    Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.menu_settings))
                }
            } else {
                // Cadenas visible directement sur l'accueil
                IconButton(onClick = actions.onToggleLock, modifier = Modifier.testTag("btn_lock")) {
                    Icon(
                        imageVector = if (isLockEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = stringResource(
                            if (isLockEnabled) R.string.cd_lock_enabled else R.string.cd_lock_disabled
                        ),
                        tint = if (isLockEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OverflowMenu(
                isDocumentOpen = isDocumentOpen,
                isDarkTheme = isDarkTheme,
                isLockEnabled = isLockEnabled,
                fontSizeSp = fontSizeSp,
                actions = actions
            )
        }
    )
}

@Composable
private fun OverflowMenu(
    isDocumentOpen: Boolean,
    isDarkTheme: Boolean,
    isLockEnabled: Boolean,
    fontSizeSp: Float,
    actions: TopBarActions
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("btn_overflow")) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (isDocumentOpen) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_text_size)) },
                leadingIcon = { Icon(Icons.Default.FormatSize, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = actions.onZoomOut, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.cd_decrease_text))
                        }
                        Text("${fontSizeSp.toInt()}", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = actions.onZoomIn, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_increase_text))
                        }
                    }
                },
                onClick = {}
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_go_to_page)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                onClick = { expanded = false; actions.onGoToPage() }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(if (isDarkTheme) R.string.menu_light_mode else R.string.menu_dark_mode)) },
            leadingIcon = { Icon(if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = null) },
            onClick = { expanded = false; actions.onToggleDarkMode() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_history)) },
            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
            onClick = { expanded = false; actions.onOpenHistory() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_pin_lock)) },
            leadingIcon = {
                Icon(
                    if (isLockEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (isLockEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                Switch(
                    checked = isLockEnabled,
                    onCheckedChange = { expanded = false; actions.onToggleLock() },
                    modifier = Modifier.testTag("switch_menu_lock")
                )
            },
            onClick = { expanded = false; actions.onToggleLock() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_settings)) },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = { expanded = false; actions.onOpenSettings() }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_quit_app), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { expanded = false; actions.onQuitApp() },
            modifier = Modifier.testTag("btn_quit_app")
        )
    }
}
