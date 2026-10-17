package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.VocalPdfApplication
import com.example.data.AppPreferences
import com.example.data.ThemeMode
import com.example.data.model.ReadingHistoryItem
import com.example.pdf.PdfDocumentReader
import com.example.service.PlaybackService
import com.example.text.SentenceSegment
import com.example.tts.TtsPlaybackManager
import com.example.util.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

/** Message à afficher, traduit au moment de l'affichage. */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<String> = emptyList(),
    val id: Long = System.nanoTime()
)

data class OpenedDocument(val uri: Uri, val title: String)

data class VocalPdfUiState(
    val document: OpenedDocument? = null,
    val currentPage: Int = 1, // 1-indexed
    val totalPages: Int = 0,
    val sentences: List<SentenceSegment> = emptyList(),
    val isLoading: Boolean = false,
    val message: UiMessage? = null,
    val fontSizeSp: Float = AppPreferences.DEFAULT_FONT_SIZE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val silentModeProtection: Boolean = true,
    val isHistoryOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val showSpeakerWarning: Boolean = false
) {
    val isDocumentOpen: Boolean get() = document != null
}

@OptIn(ExperimentalCoroutinesApi::class)
class VocalPdfViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VocalPdfApplication
    private val repository = app.historyRepository
    private val prefs = app.preferences
    val ttsManager: TtsPlaybackManager = app.ttsManager
    val lockManager = app.lockManager

    private val _uiState = MutableStateFlow(
        VocalPdfUiState(
            fontSizeSp = prefs.fontSizeSp.coerceIn(AppPreferences.MIN_FONT_SIZE, AppPreferences.MAX_FONT_SIZE),
            themeMode = prefs.themeMode,
            silentModeProtection = prefs.silentModeProtection
        )
    )
    val uiState: StateFlow<VocalPdfUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val historyList: StateFlow<List<ReadingHistoryItem>> = _searchQuery
        .flatMapLatest { query -> repository.searchHistory(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var reader: PdfDocumentReader? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var consecutiveEmptyPages = 0

    /** « Lire quand même » déjà confirmé pendant cette session. */
    private var speakerConfirmed = false
    private var pendingPlayAction: (() -> Unit)? = null

    init {
        ttsManager.onPageAdvanceRequested = { autoPlay -> onPageEnd(autoPlay) }
        ttsManager.onSentenceChanged = { index ->
            scheduleProgressSave(index)
            updateNotification()
        }
        ttsManager.onPlaybackStateChanged = { updateNotification() }
        ttsManager.onVoiceFallback = { showMessage(R.string.msg_voice_fallback) }
        ttsManager.onEmbeddedVoiceError = { reason -> showMessage(R.string.error_builtin_voice, reason) }
    }

    // =========================================================================================
    // Ouverture / navigation
    // =========================================================================================

    fun openDocumentUri(uri: Uri, initialPage: Int = 1, initialSentence: Int = 0) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            ttsManager.stop()
            val context = getApplication<Application>()
            try {
                val title = queryDocumentName(context, uri)
                val newReader = PdfDocumentReader.open(context, uri)
                if (newReader.pageCount <= 0) {
                    newReader.close()
                    _uiState.update { it.copy(isLoading = false, message = UiMessage(R.string.error_pdf_no_pages)) }
                    return@launch
                }
                reader?.close()
                reader = newReader
                ttsManager.resetForNewDocument()

                val safePage = initialPage.coerceIn(1, newReader.pageCount)
                val page = newReader.page(safePage, null)
                ttsManager.setDocumentLanguage(page.language, restartPlayback = false)
                consecutiveEmptyPages = 0

                _uiState.update {
                    it.copy(
                        document = OpenedDocument(uri, title),
                        currentPage = safePage,
                        totalPages = newReader.pageCount,
                        sentences = page.sentences,
                        isLoading = false
                    )
                }
                val startSentence = initialSentence.coerceIn(0, (page.sentences.size - 1).coerceAtLeast(0))
                ttsManager.setSentences(page.sentences, startIndex = startSentence, startSpeaking = false)
                saveProgressNow(safePage, startSentence)
                newReader.prefetch(safePage + 2)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _uiState.update { it.copy(isLoading = false, message = UiMessage(R.string.error_pdf_too_large)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, message = errorMessageFor(e)) }
            }
        }
    }

    private fun errorMessageFor(e: Exception): UiMessage = when {
        e is SecurityException || e is FileNotFoundException -> UiMessage(R.string.error_file_unavailable)
        e.javaClass.simpleName == "InvalidPasswordException" -> UiMessage(R.string.error_pdf_password)
        else -> UiMessage(R.string.error_pdf_open, listOf(e.localizedMessage ?: e.javaClass.simpleName))
    }

    fun resumeFromHistory(item: ReadingHistoryItem) {
        _uiState.update { it.copy(isHistoryOpen = false) }
        openDocumentUri(Uri.parse(item.fileUri), item.lastReadPage, item.lastReadSentenceIndex)
    }

    fun goToNextPage() {
        val state = _uiState.value
        if (state.currentPage < state.totalPages) goToPage(state.currentPage + 1, autoPlay = ttsManager.isPlaying.value)
    }

    fun goToPreviousPage() {
        val state = _uiState.value
        if (state.currentPage > 1) goToPage(state.currentPage - 1, autoPlay = ttsManager.isPlaying.value)
    }

    fun goToPage(page: Int, autoPlay: Boolean = ttsManager.isPlaying.value) {
        val r = reader ?: return
        val state = _uiState.value
        val safePage = page.coerceIn(1, state.totalPages.coerceAtLeast(1))
        // Sens de navigation : sert à sauter les pages blanches dans la bonne direction
        val step = if (safePage < state.currentPage) -1 else 1
        if (safePage == state.currentPage && state.sentences.isNotEmpty() && !state.isLoading) {
            ttsManager.jumpToSentence(0, autoPlay = autoPlay)
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            if (autoPlay) ttsManager.haltForPageChange() else ttsManager.pause()
            try {
                val extracted = r.page(safePage, ttsManager.documentLanguage.value)
                extracted.language?.let { ttsManager.setDocumentLanguage(it, restartPlayback = false) }
                _uiState.update {
                    it.copy(currentPage = safePage, sentences = extracted.sentences, isLoading = false)
                }
                saveProgressNow(safePage, 0)
                r.prefetch(safePage + 1)
                r.prefetch(safePage + 2)

                // Pause demandée (ou appel entrant) pendant le chargement : on ne relance pas
                val keepPlaying = autoPlay && ttsManager.isPlaying.value

                if (extracted.sentences.isEmpty() && keepPlaying) {
                    // Page blanche ou scannée : on passe à la suivante sans interrompre l'écoute
                    consecutiveEmptyPages++
                    val nextPage = safePage + step
                    if (nextPage in 1.._uiState.value.totalPages && consecutiveEmptyPages < MAX_EMPTY_PAGES_SKIPPED) {
                        goToPage(nextPage, autoPlay = true)
                    } else {
                        consecutiveEmptyPages = 0
                        ttsManager.setSentences(emptyList())
                        ttsManager.finishPlayback()
                    }
                    return@launch
                }
                consecutiveEmptyPages = 0
                ttsManager.setSentences(extracted.sentences, startIndex = 0, startSpeaking = keepPlaying)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                ttsManager.pause()
                _uiState.update { it.copy(isLoading = false, message = UiMessage(R.string.error_pdf_too_large)) }
            } catch (e: Exception) {
                ttsManager.pause()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = UiMessage(R.string.error_pdf_read_page, listOf(safePage.toString(), e.localizedMessage.orEmpty()))
                    )
                }
            }
        }
    }

    private fun onPageEnd(autoPlay: Boolean) {
        val state = _uiState.value
        when {
            state.document == null -> ttsManager.finishPlayback()
            state.currentPage < state.totalPages -> goToPage(state.currentPage + 1, autoPlay)
            ttsManager.isLoopModeEnabled.value -> goToPage(1, autoPlay)
            else -> {
                ttsManager.finishPlayback()
                if (autoPlay) showMessage(R.string.msg_document_finished)
            }
        }
    }

    fun closeDocument() {
        loadJob?.cancel()
        saveJob?.cancel()
        // Sauvegarde immédiate de la position exacte avant de fermer
        val state = _uiState.value
        val doc = state.document
        if (doc != null) {
            val sentence = ttsManager.currentSentenceIndex.value
            viewModelScope.launch {
                runCatching {
                    repository.saveOrUpdateProgress(doc.title, doc.uri.toString(), state.totalPages, state.currentPage, sentence)
                }
            }
        }
        ttsManager.stop()
        ttsManager.setSentences(emptyList())
        PlaybackService.stop(getApplication())
        reader?.close()
        reader = null
        _uiState.update {
            it.copy(document = null, currentPage = 1, totalPages = 0, sentences = emptyList(), isLoading = false)
        }
    }

    // =========================================================================================
    // Lecture (avec protection « mode silencieux »)
    // =========================================================================================

    private fun guardedPlay(action: () -> Unit) {
        if (_uiState.value.silentModeProtection && !speakerConfirmed && !ttsManager.isPlaying.value &&
            ttsManager.isSilentWithoutHeadset()
        ) {
            pendingPlayAction = action
            _uiState.update { it.copy(showSpeakerWarning = true) }
        } else {
            action()
        }
    }

    fun togglePlayPause() {
        if (ttsManager.isPlaying.value) ttsManager.pause() else guardedPlay { ttsManager.play() }
    }

    fun playFromSentence(index: Int) {
        if (ttsManager.isPlaying.value) ttsManager.jumpToSentence(index, autoPlay = true)
        else guardedPlay { ttsManager.jumpToSentence(index, autoPlay = true) }
    }

    fun confirmSpeakerPlayback() {
        speakerConfirmed = true
        _uiState.update { it.copy(showSpeakerWarning = false) }
        pendingPlayAction?.invoke()
        pendingPlayAction = null
    }

    fun dismissSpeakerWarning() {
        pendingPlayAction = null
        _uiState.update { it.copy(showSpeakerWarning = false) }
    }

    fun setSilentModeProtection(enabled: Boolean) {
        prefs.silentModeProtection = enabled
        if (enabled) speakerConfirmed = false
        _uiState.update { it.copy(silentModeProtection = enabled) }
    }

    // =========================================================================================
    // Affichage et panneaux
    // =========================================================================================

    fun setFontSize(size: Float) {
        val clamped = size.coerceIn(AppPreferences.MIN_FONT_SIZE, AppPreferences.MAX_FONT_SIZE)
        prefs.fontSizeSp = clamped
        _uiState.update { it.copy(fontSizeSp = clamped) }
    }

    fun zoomIn() = setFontSize(_uiState.value.fontSizeSp + 2f)
    fun zoomOut() = setFontSize(_uiState.value.fontSizeSp - 2f)

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setHistoryOpen(open: Boolean) = _uiState.update { it.copy(isHistoryOpen = open) }
    fun setSettingsOpen(open: Boolean) = _uiState.update { it.copy(isSettingsOpen = open) }
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun showMessage(@StringRes resId: Int, vararg args: String) =
        _uiState.update { it.copy(message = UiMessage(resId, args.toList())) }

    fun consumeMessage(id: Long) = _uiState.update { if (it.message?.id == id) it.copy(message = null) else it }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    // =========================================================================================
    // Progression et notification
    // =========================================================================================

    private fun scheduleProgressSave(sentenceIndex: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            saveProgressNow(_uiState.value.currentPage, sentenceIndex)
        }
    }

    private suspend fun saveProgressNow(page: Int, sentenceIndex: Int) {
        val state = _uiState.value
        val doc = state.document ?: return
        try {
            repository.saveOrUpdateProgress(
                title = doc.title,
                fileUri = doc.uri.toString(),
                totalPages = state.totalPages,
                lastReadPage = page,
                lastReadSentenceIndex = sentenceIndex
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun updateNotification() {
        val state = _uiState.value
        val doc = state.document ?: return
        val res = AppLanguage.localizedContext(getApplication())
        val sentence = ttsManager.getCurrentSentenceText().ifBlank { res.getString(R.string.notification_default_text) }
        val pageInfo = res.getString(R.string.page_info_format, state.currentPage, state.totalPages)
        PlaybackService.show(getApplication(), doc.title, sentence, pageInfo, ttsManager.isPlaying.value)
    }

    private fun queryDocumentName(context: Context, uri: Uri): String {
        var name = "Document.pdf"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            }
        }
        return name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { name }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.onPageAdvanceRequested = null
        ttsManager.onSentenceChanged = null
        ttsManager.onPlaybackStateChanged = null
        ttsManager.onVoiceFallback = null
        ttsManager.onEmbeddedVoiceError = null
        // L'interface est détruite (app fermée) : on arrête proprement la lecture
        ttsManager.stop()
        PlaybackService.stop(getApplication())
        reader?.close()
    }

    companion object {
        private const val MAX_EMPTY_PAGES_SKIPPED = 25
    }
}
