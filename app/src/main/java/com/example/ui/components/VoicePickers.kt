package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.tts.RecommendedEngines
import com.example.tts.TtsPlaybackManager
import com.example.tts.VoiceGender
import com.example.tts.VoiceItem
import com.example.util.AppLanguage
import java.util.Locale

@Composable
private fun currentLocale(): Locale =
    ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()

/** Nom d'une langue dans la langue de l'interface (« anglais », « English »…). */
@Composable
fun languageDisplayName(code: String): String {
    val locale = currentLocale()
    return Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/** « Français (France) · Voix 2 » */
@Composable
fun voiceLabel(voice: VoiceItem): String {
    val locale = currentLocale()
    val language = voice.locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    val country = voice.locale.getDisplayCountry(locale)
    val base = if (country.isNotBlank()) "$language ($country)" else language
    return if (voice.isEmbedded) {
        "$base · " + stringResource(R.string.voice_builtin)
    } else {
        "$base · " + stringResource(R.string.voice_number, voice.number)
    }
}

// ------------------------------------------------------------------------------------------
// Choix de la voix
// ------------------------------------------------------------------------------------------

@Composable
fun VoicePickerDialog(
    ttsManager: TtsPlaybackManager,
    onInstallVoices: () -> Unit,
    onDismiss: () -> Unit
) {
    val voices by ttsManager.voices.collectAsStateWithLifecycle()
    val activeVoiceName by ttsManager.activeVoiceName.collectAsStateWithLifecycle()
    val documentLanguage by ttsManager.documentLanguage.collectAsStateWithLifecycle()
    val appLanguage = currentLocale().language

    val languages = remember(voices, documentLanguage, appLanguage) {
        val present = voices.map { it.language }.distinct()
        val priority = listOfNotNull(documentLanguage, appLanguage) + AppLanguage.SUPPORTED
        present.sortedWith(
            compareBy<String> { lang -> priority.indexOf(lang).let { if (it < 0) Int.MAX_VALUE else it } }
                .thenBy { it }
        )
    }
    var filter by remember {
        mutableStateOf(
            listOfNotNull(documentLanguage, appLanguage).firstOrNull { lang -> voices.any { it.language == lang } }
        )
    }
    val shown = if (filter == null) voices else voices.filter { it.language == filter }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_picker_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = filter == null,
                            onClick = { filter = null },
                            label = { Text(stringResource(R.string.voice_filter_all)) }
                        )
                    }
                    items(languages) { lang ->
                        FilterChip(
                            selected = filter == lang,
                            onClick = { filter = lang },
                            label = { Text(languageDisplayName(lang)) }
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(top = 6.dp))
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.voice_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)) {
                        items(shown, key = { it.name }) { voice ->
                            VoiceRow(
                                voice = voice,
                                isSelected = voice.name == activeVoiceName,
                                onSelect = {
                                    ttsManager.selectVoice(voice.name)
                                    onDismiss()
                                },
                                onPreview = { ttsManager.playVoiceSample(voice.name) }
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onInstallVoices,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_more_voices))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun VoiceRow(voice: VoiceItem, isSelected: Boolean, onSelect: () -> Unit, onPreview: () -> Unit) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
    ) {
        Row(modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = voiceLabel(voice),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (voice.isEmbedded) Badge(stringResource(R.string.voice_builtin_badge))
                    if (voice.isHighQuality) Badge(stringResource(R.string.voice_hd_badge))
                    when (voice.gender) {
                        VoiceGender.FEMALE -> Badge(stringResource(R.string.gender_female))
                        VoiceGender.MALE -> Badge(stringResource(R.string.gender_male))
                        VoiceGender.UNKNOWN -> {}
                    }
                    if (voice.variant.isNotBlank()) {
                        Text(voice.variant, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onPreview) {
                Icon(Icons.Default.Hearing, contentDescription = stringResource(R.string.voice_sample_btn_desc), tint = MaterialTheme.colorScheme.secondary)
            }
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

// ------------------------------------------------------------------------------------------
// Choix du moteur de synthèse
// ------------------------------------------------------------------------------------------

@Composable
fun EnginePickerDialog(
    ttsManager: TtsPlaybackManager,
    onOpenUri: (primary: String, fallback: String) -> Unit,
    onOpenTtsSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val engines by ttsManager.engines.collectAsStateWithLifecycle()
    val currentEngine by ttsManager.currentEngine.collectAsStateWithLifecycle()
    val recommendedPackages = RecommendedEngines.all.map { it.packageName }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.engine_picker_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
        text = {
            Column(modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.engine_builtin_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                engines.forEach { engine ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { ttsManager.selectEngine(engine.packageName) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = engine.packageName == currentEngine, onClick = { ttsManager.selectEngine(engine.packageName) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(engine.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            if (engine.packageName in recommendedPackages) {
                                Text(
                                    stringResource(R.string.engine_recommended_tag),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                val toInstall = RecommendedEngines.all.filter { rec -> engines.none { it.packageName == rec.packageName } }
                if (toInstall.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.engine_recommended),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                    toInstall.forEach { rec ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(rec.displayName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                Text(stringResource(rec.descriptionRes), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onOpenUri(rec.storeUri, rec.webUri) }) {
                                    Text(stringResource(R.string.engine_install))
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.engine_after_install),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                TextButton(onClick = onOpenTtsSettings, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.engine_tts_settings))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

// ------------------------------------------------------------------------------------------
// Langue de l'application
// ------------------------------------------------------------------------------------------

@Composable
fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val current = AppLanguage.current()
    val options: List<String?> = listOf<String?>(null) + AppLanguage.SUPPORTED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_language_label)) },
        text = {
            Column {
                options.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onDismiss()
                                if (tag != current) AppLanguage.set(tag)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = tag == current, onClick = {
                            onDismiss()
                            if (tag != current) AppLanguage.set(tag)
                        })
                        Text(if (tag == null) stringResource(R.string.language_system) else AppLanguage.nativeName(tag))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
