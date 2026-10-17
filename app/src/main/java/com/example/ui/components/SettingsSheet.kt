package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.data.AppPreferences
import com.example.data.ThemeMode
import com.example.tts.TtsPlaybackManager
import com.example.util.AppLanguage

/** Actions qui ouvrent un écran externe (gérées par l'Activity). */
data class SettingsExternalActions(
    val onInstallVoices: () -> Unit,
    val onOpenUri: (primary: String, fallback: String) -> Unit,
    val onOpenTtsSettings: () -> Unit,
    val onTogglePinLock: () -> Unit,
    val onChangePin: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    ttsManager: TtsPlaybackManager,
    themeMode: ThemeMode,
    fontSizeSp: Float,
    isLockEnabled: Boolean,
    silentModeProtection: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onSilentModeProtectionChange: (Boolean) -> Unit,
    external: SettingsExternalActions,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val speechRate by ttsManager.speechRate.collectAsStateWithLifecycle()
    val pitch by ttsManager.pitch.collectAsStateWithLifecycle()
    val autoAdvance by ttsManager.autoAdvance.collectAsStateWithLifecycle()
    val loopMode by ttsManager.isLoopModeEnabled.collectAsStateWithLifecycle()
    val autoVoice by ttsManager.autoVoiceByLanguage.collectAsStateWithLifecycle()
    val voices by ttsManager.voices.collectAsStateWithLifecycle()
    val activeVoiceName by ttsManager.activeVoiceName.collectAsStateWithLifecycle()
    val engines by ttsManager.engines.collectAsStateWithLifecycle()
    val currentEngine by ttsManager.currentEngine.collectAsStateWithLifecycle()

    var showVoicePicker by remember { mutableStateOf(false) }
    var showEnginePicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("sheet_settings")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // ------------------------------------------------------------------ Voix
            SectionHeader(Icons.Default.RecordVoiceOver, stringResource(R.string.section_voice))

            val engineLabel = engines.firstOrNull { it.packageName == currentEngine }?.label
                ?: stringResource(R.string.engine_default)
            ClickableRow(
                title = stringResource(R.string.engine_label),
                value = engineLabel,
                onClick = { showEnginePicker = true },
                modifier = Modifier.testTag("row_engine")
            )
            val activeVoice = voices.firstOrNull { it.name == activeVoiceName }
            ClickableRow(
                title = stringResource(R.string.voice_label),
                value = activeVoice?.let { voiceLabel(it) } ?: stringResource(R.string.voice_default_system),
                onClick = { showVoicePicker = true },
                modifier = Modifier.testTag("btn_select_voice")
            )
            SwitchRow(
                title = stringResource(R.string.voice_auto_language_title),
                description = stringResource(R.string.voice_auto_language_desc),
                checked = autoVoice,
                onCheckedChange = { ttsManager.setAutoVoiceByLanguage(it) },
                modifier = Modifier.testTag("switch_auto_voice")
            )
            Spacer(modifier = Modifier.height(8.dp))
            NaturalVoicesCard(onSeeEngines = { showEnginePicker = true }, onInstallVoices = external.onInstallVoices)

            SectionDivider()

            // ------------------------------------------------------------------ Lecture
            SectionHeader(Icons.Default.Tune, stringResource(R.string.section_reading))

            var rateDraft by remember { mutableFloatStateOf(speechRate) }
            LaunchedEffect(speechRate) { rateDraft = speechRate }
            LabeledValue(stringResource(R.string.speed_label), formatRate(rateDraft))
            Slider(
                value = rateDraft,
                onValueChange = { rateDraft = it },
                onValueChangeFinished = { ttsManager.setSpeechRate(rateDraft) },
                valueRange = 0.5f..2.5f,
                steps = 19,
                modifier = Modifier.testTag("slider_speech_rate")
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { preset ->
                    FilterChip(
                        selected = kotlin.math.abs(speechRate - preset) < 0.05f,
                        onClick = { ttsManager.setSpeechRate(preset) },
                        label = { Text(formatRate(preset)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            // Les voix intégrées (Piper) n'ont pas de réglage de tonalité
            val pitchAvailable = activeVoice?.isEmbedded != true
            var pitchDraft by remember { mutableFloatStateOf(pitch) }
            LaunchedEffect(pitch) { pitchDraft = pitch }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabeledValue(stringResource(R.string.pitch_label), formatRate(pitchDraft), modifier = Modifier.weight(1f))
                TextButton(onClick = { ttsManager.setPitch(1.0f) }, enabled = pitchAvailable && pitch != 1.0f) {
                    Text(stringResource(R.string.btn_reset))
                }
            }
            Slider(
                value = pitchDraft,
                enabled = pitchAvailable,
                onValueChange = { pitchDraft = it },
                onValueChangeFinished = { ttsManager.setPitch(pitchDraft) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.testTag("slider_pitch")
            )
            if (!pitchAvailable) {
                Text(
                    text = stringResource(R.string.pitch_unavailable_builtin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SwitchRow(
                title = stringResource(R.string.auto_advance_title),
                description = stringResource(R.string.auto_advance_desc),
                checked = autoAdvance,
                onCheckedChange = { ttsManager.setAutoAdvance(it) },
                modifier = Modifier.testTag("switch_auto_advance")
            )
            SwitchRow(
                title = stringResource(R.string.loop_mode_title),
                description = stringResource(R.string.loop_mode_desc),
                checked = loopMode,
                onCheckedChange = { ttsManager.setLoopMode(it) },
                modifier = Modifier.testTag("switch_loop_mode")
            )

            SectionDivider()

            // ------------------------------------------------------------------ Affichage
            SectionHeader(Icons.Default.Palette, stringResource(R.string.section_display))
            Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(modifier = Modifier.height(6.dp))
            val themes = listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themes.size)
                    ) { Text(stringResource(label)) }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            var fontDraft by remember { mutableFloatStateOf(fontSizeSp) }
            LaunchedEffect(fontSizeSp) { fontDraft = fontSizeSp }
            LabeledValue(stringResource(R.string.text_size_label), "${fontDraft.toInt()}")
            Slider(
                value = fontDraft,
                onValueChange = { fontDraft = it },
                onValueChangeFinished = { onFontSizeChange(fontDraft) },
                valueRange = AppPreferences.MIN_FONT_SIZE..AppPreferences.MAX_FONT_SIZE
            )
            Text(
                text = stringResource(R.string.text_preview),
                fontSize = fontDraft.sp,
                lineHeight = (fontDraft * 1.5f).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionDivider()

            // ------------------------------------------------------------------ Langue
            SectionHeader(Icons.Default.Language, stringResource(R.string.section_language))
            val currentLanguage = AppLanguage.current()
            ClickableRow(
                title = stringResource(R.string.app_language_label),
                value = currentLanguage?.let { AppLanguage.nativeName(it) } ?: stringResource(R.string.language_system),
                onClick = { showLanguagePicker = true },
                modifier = Modifier.testTag("row_app_language")
            )

            SectionDivider()

            // ------------------------------------------------------------------ Sécurité et discrétion
            SectionHeader(Icons.Default.Lock, stringResource(R.string.section_privacy))
            SwitchRow(
                title = stringResource(R.string.pin_lock_title),
                description = stringResource(R.string.pin_lock_desc),
                checked = isLockEnabled,
                onCheckedChange = { external.onTogglePinLock() },
                modifier = Modifier.testTag("switch_pin_lock")
            )
            if (isLockEnabled) {
                TextButton(onClick = external.onChangePin) { Text(stringResource(R.string.btn_change_pin)) }
            }
            SwitchRow(
                title = stringResource(R.string.silent_protection_title),
                description = stringResource(R.string.silent_protection_desc),
                checked = silentModeProtection,
                onCheckedChange = onSilentModeProtectionChange,
                modifier = Modifier.testTag("switch_silent_protection")
            )
            InfoLine(Icons.Default.Headphones, stringResource(R.string.headphone_pause_info))

            SectionDivider()

            // ------------------------------------------------------------------ À propos
            SectionHeader(Icons.Default.Info, stringResource(R.string.section_about))
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.about_offline), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.about_voices_credits),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showVoicePicker) {
        VoicePickerDialog(ttsManager = ttsManager, onInstallVoices = external.onInstallVoices, onDismiss = { showVoicePicker = false })
    }
    if (showEnginePicker) {
        EnginePickerDialog(
            ttsManager = ttsManager,
            onOpenUri = external.onOpenUri,
            onOpenTtsSettings = external.onOpenTtsSettings,
            onDismiss = { showEnginePicker = false }
        )
    }
    if (showLanguagePicker) {
        LanguagePickerDialog(onDismiss = { showLanguagePicker = false })
    }
}

// ------------------------------------------------------------------------------------------
// Éléments réutilisables
// ------------------------------------------------------------------------------------------

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ClickableRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NaturalVoicesCard(onSeeEngines: () -> Unit, onInstallVoices: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.natural_voices_card_title),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.natural_voices_card_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSeeEngines) { Text(stringResource(R.string.btn_see_engines)) }
                OutlinedButton(onClick = onInstallVoices) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_more_voices))
                }
            }
        }
    }
}
