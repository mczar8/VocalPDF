package com.example

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ThemeMode
import com.example.service.PlaybackService
import com.example.ui.components.BottomPlayerBar
import com.example.ui.components.DocumentView
import com.example.ui.components.GoToPageDialog
import com.example.ui.components.HistoryDialog
import com.example.ui.components.HomeScreen
import com.example.ui.components.SettingsExternalActions
import com.example.ui.components.SettingsSheet
import com.example.ui.components.SpeakerWarningDialog
import com.example.ui.components.TopBarActions
import com.example.ui.components.VocalTopAppBar
import com.example.ui.components.languageDisplayName
import com.example.ui.lock.LockScreen
import com.example.ui.lock.PinFlowDialog
import com.example.ui.lock.PinFlowMode
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.VocalPdfViewModel

class MainActivity : AppCompatActivity() {

    private val viewModel: VocalPdfViewModel by viewModels()
    private val lockManager get() = viewModel.lockManager
    private var refreshVoicesOnReturn = false

    private val quitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackService.ACTION_QUIT_APP_BROADCAST) quitApp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this,
            quitReceiver,
            IntentFilter(PlaybackService.ACTION_QUIT_APP_BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent { VocalPdfApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        lockManager.onAppStarted()
        if (refreshVoicesOnReturn) {
            refreshVoicesOnReturn = false
            viewModel.ttsManager.refreshVoices()
        }
    }

    override fun onResume() {
        super.onResume()
        // Une boîte de dialogue système (permission…) n'arrête pas l'activité : on oublie le marqueur
        lockManager.cancelInternalLeave()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) lockManager.onAppStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(quitReceiver) }
    }

    /** « Ouvrir avec VocalPDF » / « Partager vers VocalPDF » depuis une autre application. */
    private fun handleIncomingIntent(incoming: Intent?) {
        val intent = incoming ?: return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        viewModel.openDocumentUri(uri)
    }

    private fun quitApp() {
        viewModel.ttsManager.stop()
        PlaybackService.stop(this)
        finishAndRemoveTask()
    }

    /** Ouvre un écran externe sans redemander le code PIN au retour. */
    private fun launchExternal(vararg intents: Intent) {
        lockManager.markLeavingForInternalActivity()
        refreshVoicesOnReturn = true
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        viewModel.showMessage(R.string.error_no_app)
    }

    private fun openInstallVoices() = launchExternal(
        viewModel.ttsManager.installVoiceDataIntent(),
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent(TTS_SETTINGS_ACTION)
    )

    private fun openTtsSettings() = launchExternal(Intent(TTS_SETTINGS_ACTION))

    private fun openUri(primary: String, fallback: String) = launchExternal(
        Intent(Intent.ACTION_VIEW, Uri.parse(primary)),
        Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
    )

    @Composable
    private fun VocalPdfApp() {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val historyList by viewModel.historyList.collectAsStateWithLifecycle()
        val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
        val isLocked by lockManager.isLocked.collectAsStateWithLifecycle()
        val isLockEnabled by lockManager.isEnabled.collectAsStateWithLifecycle()

        val tts = viewModel.ttsManager
        val isPlaying by tts.isPlaying.collectAsStateWithLifecycle()
        val currentSentenceIndex by tts.currentSentenceIndex.collectAsStateWithLifecycle()
        val speechRate by tts.speechRate.collectAsStateWithLifecycle()
        val autoAdvance by tts.autoAdvance.collectAsStateWithLifecycle()
        val documentLanguage by tts.documentLanguage.collectAsStateWithLifecycle()
        val missingVoiceLanguage by tts.missingVoiceLanguage.collectAsStateWithLifecycle()

        val darkTheme = when (uiState.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        DisposableEffect(darkTheme) {
            val style = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose {}
        }

        val context = LocalContext.current
        val snackbarHostState = remember { SnackbarHostState() }
        var pinFlow by rememberSaveable { mutableStateOf<PinFlowMode?>(null) }
        var showGoToPage by rememberSaveable { mutableStateOf(false) }

        val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    viewModel.showMessage(R.string.error_uri_permission)
                }
                viewModel.openDocumentUri(uri)
            }
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        var notificationAsked by rememberSaveable { mutableStateOf(false) }

        // La permission de notification est demandée au premier lancement de lecture
        LaunchedEffect(isPlaying) {
            if (isPlaying && !notificationAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationAsked = true
                lockManager.markLeavingForInternalActivity()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        LaunchedEffect(uiState.message?.id) {
            val message = uiState.message ?: return@LaunchedEffect
            val text = context.getString(message.resId, *message.args.toTypedArray())
            viewModel.consumeMessage(message.id)
            snackbarHostState.showSnackbar(text)
        }

        // Retour système avec un document ouvert : retour à l'accueil (comme la flèche)
        BackHandler(enabled = uiState.isDocumentOpen && !isLocked) { viewModel.closeDocument() }

        MyApplicationTheme(darkTheme = darkTheme) {
            if (isLocked) {
                LockScreen(lockManager)
            } else {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        VocalTopAppBar(
                            title = uiState.document?.title.orEmpty(),
                            isDocumentOpen = uiState.isDocumentOpen,
                            currentPage = uiState.currentPage,
                            totalPages = uiState.totalPages,
                            languageName = documentLanguage?.let { languageDisplayName(it) },
                            isDarkTheme = darkTheme,
                            isLockEnabled = isLockEnabled,
                            fontSizeSp = uiState.fontSizeSp,
                            actions = TopBarActions(
                                onCloseDocument = { viewModel.closeDocument() },
                                onZoomIn = { viewModel.zoomIn() },
                                onZoomOut = { viewModel.zoomOut() },
                                onToggleDarkMode = { viewModel.setThemeMode(if (darkTheme) ThemeMode.LIGHT else ThemeMode.DARK) },
                                onOpenSettings = { viewModel.setSettingsOpen(true) },
                                onOpenHistory = { viewModel.setHistoryOpen(true) },
                                onGoToPage = { showGoToPage = true },
                                onToggleLock = { pinFlow = if (isLockEnabled) PinFlowMode.DISABLE else PinFlowMode.ENABLE },
                                onQuitApp = { quitApp() }
                            )
                        )
                    },
                    bottomBar = {
                        if (uiState.isDocumentOpen) {
                            BottomPlayerBar(
                                isPlaying = isPlaying,
                                currentSentenceIndex = currentSentenceIndex,
                                totalSentences = uiState.sentences.size,
                                speechRate = speechRate,
                                autoAdvance = autoAdvance,
                                onTogglePlay = { viewModel.togglePlayPause() },
                                onPrevious = { tts.previousSentence() },
                                onNext = { tts.nextSentence() },
                                onOpenSettings = { viewModel.setSettingsOpen(true) },
                                onToggleAutoAdvance = { tts.setAutoAdvance(it) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (!uiState.isDocumentOpen) {
                            HomeScreen(
                                onPickPdf = {
                                    lockManager.markLeavingForInternalActivity()
                                    pdfPicker.launch(arrayOf("application/pdf"))
                                },
                                recentHistory = historyList,
                                onResumeDocument = { viewModel.resumeFromHistory(it) },
                                onOpenHistory = { viewModel.setHistoryOpen(true) }
                            )
                        } else {
                            DocumentView(
                                currentPage = uiState.currentPage,
                                totalPages = uiState.totalPages,
                                sentences = uiState.sentences,
                                activeSentenceIndex = currentSentenceIndex,
                                isPlaying = isPlaying,
                                fontSizeSp = uiState.fontSizeSp,
                                isDarkMode = darkTheme,
                                isLoading = uiState.isLoading,
                                missingVoiceLanguageName = missingVoiceLanguage?.let { languageDisplayName(it) },
                                onInstallVoice = { openInstallVoices() },
                                onPreviousPage = { viewModel.goToPreviousPage() },
                                onNextPage = { viewModel.goToNextPage() },
                                onPageBadgeClick = { showGoToPage = true },
                                onSentenceClick = { index -> viewModel.playFromSentence(index) }
                            )
                        }
                    }
                }

                // --- Panneaux et dialogues ---
                if (uiState.isSettingsOpen) {
                    SettingsSheet(
                        ttsManager = tts,
                        themeMode = uiState.themeMode,
                        fontSizeSp = uiState.fontSizeSp,
                        isLockEnabled = isLockEnabled,
                        silentModeProtection = uiState.silentModeProtection,
                        onThemeModeChange = { viewModel.setThemeMode(it) },
                        onFontSizeChange = { viewModel.setFontSize(it) },
                        onSilentModeProtectionChange = { viewModel.setSilentModeProtection(it) },
                        external = SettingsExternalActions(
                            onInstallVoices = { openInstallVoices() },
                            onOpenUri = { primary, fallback -> openUri(primary, fallback) },
                            onOpenTtsSettings = { openTtsSettings() },
                            onTogglePinLock = { pinFlow = if (isLockEnabled) PinFlowMode.DISABLE else PinFlowMode.ENABLE },
                            onChangePin = { pinFlow = PinFlowMode.CHANGE }
                        ),
                        onDismiss = { viewModel.setSettingsOpen(false) }
                    )
                }

                if (uiState.isHistoryOpen) {
                    HistoryDialog(
                        historyList = historyList,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        onResumeDocument = { viewModel.resumeFromHistory(it) },
                        onDeleteItem = { viewModel.deleteHistoryItem(it) },
                        onClearAll = { viewModel.clearAllHistory() },
                        onDismiss = { viewModel.setHistoryOpen(false) }
                    )
                }

                if (showGoToPage && uiState.isDocumentOpen) {
                    GoToPageDialog(
                        currentPage = uiState.currentPage,
                        totalPages = uiState.totalPages,
                        onGo = { viewModel.goToPage(it) },
                        onDismiss = { showGoToPage = false }
                    )
                }

                if (uiState.showSpeakerWarning) {
                    SpeakerWarningDialog(
                        onConfirm = { viewModel.confirmSpeakerPlayback() },
                        onDismiss = { viewModel.dismissSpeakerWarning() }
                    )
                }

                pinFlow?.let { mode ->
                    PinFlowDialog(mode = mode, lockManager = lockManager) { messageRes ->
                        pinFlow = null
                        messageRes?.let { viewModel.showMessage(it) }
                    }
                }
            }
        }
    }

    companion object {
        private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
    }
}
