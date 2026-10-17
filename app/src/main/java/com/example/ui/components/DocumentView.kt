package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.text.SegmentKind
import com.example.text.SentenceSegment
import com.example.ui.theme.HighlightDarkBg
import com.example.ui.theme.HighlightDarkBorder
import com.example.ui.theme.HighlightLightBg
import com.example.ui.theme.HighlightLightBorder

@Composable
fun DocumentView(
    currentPage: Int,
    totalPages: Int,
    sentences: List<SentenceSegment>,
    activeSentenceIndex: Int,
    isPlaying: Boolean,
    fontSizeSp: Float,
    isDarkMode: Boolean,
    isLoading: Boolean,
    missingVoiceLanguageName: String?,
    onInstallVoice: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageBadgeClick: () -> Unit,
    onSentenceClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Suit la phrase en cours de lecture
    LaunchedEffect(activeSentenceIndex, sentences) {
        if (sentences.isNotEmpty() && activeSentenceIndex in sentences.indices) {
            listState.animateScrollToItem((activeSentenceIndex - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        PageStrip(currentPage, totalPages, isLoading, onPreviousPage, onNextPage, onPageBadgeClick)

        if (missingVoiceLanguageName != null) {
            MissingVoiceBanner(missingVoiceLanguageName, onInstallVoice)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.loading_page),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                sentences.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.empty_page),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("reading_sentences_list")
                ) {
                    itemsIndexed(sentences, key = { _, s -> s.index }) { index, sentence ->
                        SentenceRow(
                            sentence = sentence,
                            isActive = index == activeSentenceIndex,
                            isPlaying = isPlaying,
                            fontSizeSp = fontSizeSp,
                            isDarkMode = isDarkMode,
                            onClick = { onSentenceClick(sentence.index) },
                            modifier = Modifier.testTag("sentence_item_$index")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageStrip(
    currentPage: Int,
    totalPages: Int,
    isLoading: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageBadgeClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = onPreviousPage,
                enabled = currentPage > 1 && !isLoading,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("btn_page_prev")
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.page_prev), style = MaterialTheme.typography.labelMedium)
            }

            // Toucher le numéro de page ouvre « Aller à la page »
            Surface(
                onClick = onPageBadgeClick,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.testTag("badge_page_counter")
            ) {
                Text(
                    text = stringResource(R.string.page_counter, currentPage, totalPages),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            OutlinedButton(
                onClick = onNextPage,
                enabled = currentPage < totalPages && !isLoading,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("btn_page_next")
            ) {
                Text(stringResource(R.string.page_next), style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MissingVoiceBanner(languageName: String, onInstallVoice: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.missing_voice_banner, languageName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onInstallVoice) { Text(stringResource(R.string.btn_install_voice)) }
        }
    }
}

@Composable
private fun SentenceRow(
    sentence: SentenceSegment,
    isActive: Boolean,
    isPlaying: Boolean,
    fontSizeSp: Float,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(
        targetValue = when {
            isActive && isDarkMode -> HighlightDarkBg
            isActive -> HighlightLightBg
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "highlight_bg"
    )
    val border by animateColorAsState(
        targetValue = when {
            isActive && isDarkMode -> HighlightDarkBorder
            isActive -> HighlightLightBorder
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "highlight_border"
    )
    val isHeading = sentence.kind == SegmentKind.HEADING
    val size = if (isHeading) fontSizeSp * 1.15f else fontSizeSp
    val topSpace = if (isHeading) 10.dp else 0.dp
    val startIndent = if (sentence.kind == SegmentKind.LIST_ITEM) 8.dp else 0.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topSpace, start = startIndent)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = if (isActive) BorderStroke(1.5.dp, border) else null
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            if (isActive && isPlaying) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.cd_reading_now),
                    tint = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .padding(top = 2.dp, end = 8.dp)
                        .size(20.dp)
                )
            }
            Text(
                text = sentence.displayText,
                fontSize = size.sp,
                lineHeight = (size * 1.5f).sp,
                fontWeight = when {
                    isHeading -> FontWeight.Bold
                    isActive -> FontWeight.SemiBold
                    else -> FontWeight.Normal
                },
                color = if (isActive && !isDarkMode) Color(0xFF1C1917)
                else if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
