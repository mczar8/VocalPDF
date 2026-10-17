package com.example.tts

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AppPreferences
import com.example.text.SentenceSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceGender { MALE, FEMALE, UNKNOWN }

/** Une voix hors ligne installée sur l'appareil. */
data class VoiceItem(
    val name: String,
    val locale: Locale,
    val language: String, // code ISO 639-1 normalisé (« fr », « en »…)
    val gender: VoiceGender,
    val quality: Int,
    val variant: String,
    /** Numéro de la voix au sein de sa langue/région (pour un libellé lisible : « Voix 2 »). */
    val number: Int,
    /** Voix neuronale livrée dans l'application (Piper). */
    val isEmbedded: Boolean = false
) {
    val isHighQuality: Boolean get() = quality >= Voice.QUALITY_HIGH
}

data class EngineItem(val packageName: String, val label: String)

/**
 * Moteur de lecture vocale 100 % hors ligne.
 *
 *  - voix neuronales intégrées (français, anglais) + voix des moteurs Android installés
 *  - choix du moteur Android (Google, Samsung…) et des voix, triées par qualité
 *  - voix choisie automatiquement selon la langue du document
 *  - file d'attente de toute la page : enchaînement des phrases sans blanc ni latence
 *  - pause automatique : appel entrant, autre app audio, casque débranché
 */
class TtsPlaybackManager(context: Context, private val prefs: AppPreferences) {

    companion object {
        private const val TAG = "TtsManager"
        private const val TYPE_SENTENCE = "s"
        private const val TYPE_PAUSE = "p"
        private const val TYPE_SAMPLE = "x"

        private val SAMPLE_TEXTS = mapOf(
            "fr" to "Bonjour, voici un aperçu de cette voix pour la lecture de vos documents.",
            "en" to "Hello, this is a preview of this voice for reading your documents.",
            "es" to "Hola, esta es una muestra de esta voz para leer sus documentos.",
            "de" to "Hallo, dies ist eine Hörprobe dieser Stimme zum Vorlesen Ihrer Dokumente.",
            "it" to "Ciao, questo è un esempio di questa voce per la lettura dei tuoi documenti.",
            "pt" to "Olá, esta é uma amostra desta voz para a leitura dos seus documentos."
        )

        private val ISO3_TO_ISO1 = mapOf(
            "fra" to "fr", "fre" to "fr", "eng" to "en", "spa" to "es",
            "deu" to "de", "ger" to "de", "ita" to "it", "por" to "pt"
        )

        private val PREFERRED_COUNTRY = mapOf(
            "fr" to "FR", "en" to "US", "es" to "ES", "de" to "DE", "it" to "IT", "pt" to "PT"
        )

        private fun normalizeLanguage(locale: Locale): String {
            val lang = locale.language.lowercase(Locale.ROOT)
            return ISO3_TO_ISO1[lang] ?: lang
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var engineGeneration = 0
    private var isInitialized = false
    /** Le moteur Android a définitivement échoué à démarrer (aucun moteur utilisable). */
    private var androidEngineFailed = false
    private var pendingPlay = false
    private val voiceObjects = HashMap<String, Voice>()
    private val failedVoices = HashSet<String>()

    // --- Voix intégrées (Piper / sherpa-onnx) ---
    private val embedded = EmbeddedSpeechEngine(appContext)
    private val embeddedItems: List<VoiceItem> = EmbeddedVoices.all.map { v ->
        VoiceItem(
            name = v.id,
            locale = v.locale,
            language = v.language,
            gender = v.gender,
            quality = EmbeddedVoices.QUALITY,
            variant = "Piper",
            number = 0,
            isEmbedded = true
        )
    }

    // --- État exposé ---
    private val _engines = MutableStateFlow<List<EngineItem>>(emptyList())
    val engines: StateFlow<List<EngineItem>> = _engines.asStateFlow()

    private val _currentEngine = MutableStateFlow<String?>(null)
    val currentEngine: StateFlow<String?> = _currentEngine.asStateFlow()

    private val _voices = MutableStateFlow(sortVoices(embeddedItems))
    val voices: StateFlow<List<VoiceItem>> = _voices.asStateFlow()

    /** Dernière voix choisie manuellement. */
    private var selectedVoiceName: String? = prefs.selectedVoice

    /** Voix réellement utilisée (peut différer du choix manuel en mode automatique). */
    private val _activeVoiceName = MutableStateFlow<String?>(null)
    val activeVoiceName: StateFlow<String?> = _activeVoiceName.asStateFlow()

    private val _autoVoiceByLanguage = MutableStateFlow(prefs.autoVoiceByLanguage)
    val autoVoiceByLanguage: StateFlow<Boolean> = _autoVoiceByLanguage.asStateFlow()

    private val _documentLanguage = MutableStateFlow<String?>(null)
    val documentLanguage: StateFlow<String?> = _documentLanguage.asStateFlow()
    private var documentLanguageLocked = false

    /** Langue du document pour laquelle aucune voix n'est installée (null = tout va bien). */
    private val _missingVoiceLanguage = MutableStateFlow<String?>(null)
    val missingVoiceLanguage: StateFlow<String?> = _missingVoiceLanguage.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _speechRate = MutableStateFlow(prefs.speechRate.coerceIn(0.5f, 2.5f))
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _pitch = MutableStateFlow(prefs.pitch.coerceIn(0.5f, 2.0f))
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _autoAdvance = MutableStateFlow(prefs.autoAdvance)
    val autoAdvance: StateFlow<Boolean> = _autoAdvance.asStateFlow()

    private val _isLoopModeEnabled = MutableStateFlow(prefs.loopMode)
    val isLoopModeEnabled: StateFlow<Boolean> = _isLoopModeEnabled.asStateFlow()

    // --- Callbacks vers le ViewModel (toujours appelés sur le thread principal) ---
    /** Fin de page atteinte. autoPlay = continuer la lecture sur la page suivante. */
    var onPageAdvanceRequested: ((autoPlay: Boolean) -> Unit)? = null
    var onSentenceChanged: ((index: Int) -> Unit)? = null
    var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onVoiceFallback: (() -> Unit)? = null
    /** La voix intégrée n'a pas pu parler (raison technique courte). */
    var onEmbeddedVoiceError: ((reason: String) -> Unit)? = null

    // --- File de lecture ---
    private var sentences: List<SentenceSegment> = emptyList()
    private var session = 0
    private var lastQueuedIndex = -1
    private var isPlayingSample = false

    // --- Focus audio ---
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocusGain = false
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Appel entrant, GPS… : pause puis reprise automatique ensuite
                    if (_isPlaying.value) {
                        resumeOnFocusGain = true
                        haltSpeech(keepPlayingFlag = false)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        play()
                    }
                }
            }
        }
    }

    // Casque / écouteurs débranchés : pause immédiate (évite de lire à voix haute en pleine réunion)
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post { handleStart(utteranceId) }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post { handleDone(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.post { handleError(utteranceId, TextToSpeech.ERROR) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post { handleError(utteranceId, errorCode) }
        }
    }

    init {
        embedded.listener = object : EmbeddedSpeechEngine.Listener {
            override fun onStart(utteranceId: String) {
                mainHandler.post { handleStart(utteranceId) }
            }

            override fun onDone(utteranceId: String) {
                mainHandler.post { handleDone(utteranceId) }
            }

            override fun onError(utteranceId: String, reason: String) {
                mainHandler.post { handleEmbeddedError(utteranceId, reason) }
            }
        }
        createEngine(prefs.ttsEngine)
        // Les voix intégrées sont utilisables tout de suite, sans attendre le moteur Android
        applyBestVoice()
    }

    // =========================================================================================
    // Moteur
    // =========================================================================================

    private fun createEngine(enginePackage: String?) {
        val generation = ++engineGeneration
        isInitialized = false
        androidEngineFailed = false
        tts?.let { old -> runCatching { old.stop(); old.shutdown() } }
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post { onEngineInit(generation, status) }
        }
        tts = if (enginePackage.isNullOrBlank()) {
            TextToSpeech(appContext, listener)
        } else {
            TextToSpeech(appContext, listener, enginePackage)
        }
    }

    private fun onEngineInit(generation: Int, status: Int) {
        if (generation != engineGeneration) return
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed ($status)")
            if (!prefs.ttsEngine.isNullOrBlank()) {
                // Le moteur choisi ne répond pas : retour au moteur par défaut
                prefs.ttsEngine = null
                createEngine(null)
            } else {
                // Aucun moteur Android : les voix intégrées restent utilisables
                androidEngineFailed = true
                applyBestVoice()
                if (pendingPlay && canSpeak()) {
                    pendingPlay = false
                    play()
                }
            }
            return
        }
        isInitialized = true
        engine.setOnUtteranceProgressListener(progressListener)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        _engines.value = runCatching { engine.engines.map { EngineItem(it.name, it.label) } }.getOrDefault(emptyList())
        val chosen = prefs.ttsEngine
        _currentEngine.value = if (chosen != null && _engines.value.any { it.packageName == chosen }) {
            chosen
        } else {
            runCatching { engine.defaultEngine }.getOrNull()
        }
        loadVoices(engine)
        applyBestVoice()
        if (pendingPlay) {
            pendingPlay = false
            play()
        }
    }

    fun selectEngine(packageName: String) {
        if (packageName == _currentEngine.value && isInitialized) return
        pause()
        prefs.ttsEngine = packageName
        _voices.value = sortVoices(embeddedItems)
        voiceObjects.clear()
        failedVoices.clear()
        createEngine(packageName)
    }

    /** Intent ouvrant l'installation de voix du moteur actuel (voix HD hors ligne). */
    fun installVoiceDataIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
        _currentEngine.value?.let { setPackage(it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** À appeler au retour dans l'app (de nouvelles voix ont pu être installées). */
    fun refreshVoices() {
        val engine = tts ?: return
        if (!isInitialized) return
        loadVoices(engine)
        applyBestVoice()
    }

    // =========================================================================================
    // Voix
    // =========================================================================================

    private fun loadVoices(engine: TextToSpeech) {
        val raw = runCatching { engine.voices }.getOrNull().orEmpty()
        val availability = HashMap<Locale, Boolean>()
        val usable = raw.filter { voice ->
            runCatching {
                if (voice == null || voice.locale == null || voice.name.isNullOrBlank()) return@runCatching false
                if (voice.isNetworkConnectionRequired) return@runCatching false // 100 % hors ligne
                if (voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) return@runCatching false
                availability.getOrPut(voice.locale) {
                    engine.isLanguageAvailable(voice.locale) >= TextToSpeech.LANG_AVAILABLE
                }
            }.getOrDefault(false)
        }

        voiceObjects.clear()
        usable.forEach { voiceObjects[it.name] = it }

        val items = usable
            .groupBy { it.locale.toLanguageTag() }
            .flatMap { (_, group) ->
                group.sortedBy { it.name }.mapIndexed { i, voice ->
                    VoiceItem(
                        name = voice.name,
                        locale = voice.locale,
                        language = normalizeLanguage(voice.locale),
                        gender = detectGender(voice),
                        quality = voice.quality,
                        variant = extractVariant(voice.name),
                        number = i + 1
                    )
                }
            }
        _voices.value = sortVoices(embeddedItems + items)
    }

    private fun sortVoices(list: List<VoiceItem>): List<VoiceItem> = list.sortedWith(
        compareBy<VoiceItem> { it.locale.getDisplayLanguage(Locale.getDefault()).lowercase() }
            .thenByDescending { it.isEmbedded }
            .thenBy { it.locale.country }
            .thenByDescending { it.quality }
            .thenBy { it.number }
    )

    /** Voix intégrée actuellement utilisée, ou null si c'est une voix Android. */
    private fun activeEmbedded(): EmbeddedVoice? = EmbeddedVoices.byId(_activeVoiceName.value)

    /**
     * Prêt à parler ? Avec une voix intégrée, on attend quand même le moteur Android si l'utilisateur
     * avait choisi une voix Android (elle n'est connue qu'une fois le moteur démarré).
     */
    private fun canSpeak(): Boolean {
        if (activeEmbedded() == null) return isInitialized
        return isInitialized || androidEngineFailed || !savedChoiceIsAndroidVoice()
    }

    private fun savedChoiceIsAndroidVoice(): Boolean {
        val lang = _documentLanguage.value
        val saved = (if (_autoVoiceByLanguage.value && lang != null) prefs.preferredVoiceFor(lang) else null)
            ?: selectedVoiceName
        return saved != null && !EmbeddedVoices.isEmbedded(saved)
    }

    private fun extractVariant(name: String): String = when {
        "-x-" in name -> name.substringAfter("-x-").substringBefore("-")
        "#" in name -> name.substringAfter("#").substringBefore("-")
        else -> ""
    }

    private fun detectGender(voice: Voice): VoiceGender {
        val tokens = buildSet {
            addAll(voice.name.lowercase(Locale.ROOT).split(Regex("[^a-z]+")))
            voice.features?.forEach { addAll(it.lowercase(Locale.ROOT).split(Regex("[^a-z]+"))) }
        }
        val female = setOf("female", "woman", "femme", "frau", "mujer", "donna", "mulher")
        val male = setOf("male", "man", "homme", "mann", "hombre", "uomo", "homem")
        return when {
            tokens.any { it in female } -> VoiceGender.FEMALE
            tokens.any { it in male } -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }

    private fun preferredCountry(language: String): String? {
        val device = Locale.getDefault()
        return if (device.language == language) device.country else PREFERRED_COUNTRY[language]
    }

    private fun bestVoiceFor(language: String): VoiceItem? {
        val country = preferredCountry(language)
        return _voices.value
            .filter { it.language == language && it.name !in failedVoices }
            .sortedWith(
                compareByDescending<VoiceItem> { it.quality }
                    .thenByDescending { it.locale.country.equals(country, ignoreCase = true) }
                    .thenBy { it.number }
            )
            .firstOrNull()
    }

    private fun resolveVoice(): VoiceItem? {
        val voices = _voices.value.filter { it.name !in failedVoices }
        if (voices.isEmpty()) return null
        val lang = _documentLanguage.value
        if (_autoVoiceByLanguage.value && lang != null) {
            prefs.preferredVoiceFor(lang)?.let { name -> voices.firstOrNull { it.name == name } }?.let { return it }
            bestVoiceFor(lang)?.let { return it }
        }
        selectedVoiceName?.let { name -> voices.firstOrNull { it.name == name } }?.let { return it }
        return bestVoiceFor(Locale.getDefault().language) ?: voices.maxByOrNull { it.quality }
    }

    private fun applyBestVoice() {
        val engine = tts?.takeIf { isInitialized }
        val lang = _documentLanguage.value
        val voice = resolveVoice()
        var missing: String? = null
        if (voice != null) {
            if (lang != null && _autoVoiceByLanguage.value && voice.language != lang) missing = lang
            if (voice.isEmbedded) {
                // Chargement anticipé du modèle quand un document est ouvert
                if (sentences.isNotEmpty() && canSpeak()) EmbeddedVoices.byId(voice.name)?.let { embedded.preload(it) }
            } else if (engine != null) {
                setEngineVoice(engine, voice.name)
            }
            _activeVoiceName.value = voice.name
        } else if (engine != null) {
            // Moteur sans liste de voix : on s'appuie sur la langue
            val locale = lang?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
            val result = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (lang != null && result < TextToSpeech.LANG_AVAILABLE) missing = lang
            _activeVoiceName.value = null
        } else {
            _activeVoiceName.value = null
        }
        _missingVoiceLanguage.value = missing
    }

    private fun setEngineVoice(engine: TextToSpeech, name: String) {
        val voice = voiceObjects[name] ?: return
        runCatching { engine.voice = voice }.onFailure { Log.w(TAG, "Cannot select voice $name", it) }
    }

    /** Choix manuel d'une voix : mémorisé, y compris comme voix préférée pour sa langue. */
    fun selectVoice(voiceName: String) {
        val item = _voices.value.firstOrNull { it.name == voiceName } ?: return
        selectedVoiceName = voiceName
        prefs.selectedVoice = voiceName
        prefs.setPreferredVoiceFor(item.language, voiceName)
        failedVoices.remove(voiceName)
        val docLang = _documentLanguage.value
        if (_autoVoiceByLanguage.value && docLang != null && item.language != docLang) {
            // L'utilisateur corrige la langue détectée pour ce document
            _documentLanguage.value = item.language
            documentLanguageLocked = true
        }
        applyBestVoice()
        restartIfPlaying()
    }

    fun setAutoVoiceByLanguage(enabled: Boolean) {
        _autoVoiceByLanguage.value = enabled
        prefs.autoVoiceByLanguage = enabled
        applyBestVoice()
        restartIfPlaying()
    }

    /**
     * Langue détectée sur la page courante (ignorée si l'utilisateur l'a corrigée).
     * @param restartPlayback relancer la phrase en cours avec la nouvelle voix
     *        (false lors d'un changement de page : la nouvelle page démarre juste après)
     */
    fun setDocumentLanguage(language: String?, restartPlayback: Boolean = true) {
        if (documentLanguageLocked || language == null || language == _documentLanguage.value) return
        _documentLanguage.value = language
        applyBestVoice()
        if (restartPlayback) restartIfPlaying()
    }

    fun resetForNewDocument() {
        documentLanguageLocked = false
        _documentLanguage.value = null
        _missingVoiceLanguage.value = null
        applyBestVoice()
    }

    fun playVoiceSample(voiceName: String) {
        val item = _voices.value.firstOrNull { it.name == voiceName } ?: return
        val text = SAMPLE_TEXTS[item.language] ?: SAMPLE_TEXTS.getValue("en")

        EmbeddedVoices.byId(voiceName)?.let { embeddedVoice ->
            if (_isPlaying.value) pause()
            session++
            runCatching { tts?.stop() }
            requestAudioFocus()
            isPlayingSample = true
            embedded.speak(
                embeddedVoice,
                listOf(EmbeddedSpeechEngine.Utterance("$TYPE_SAMPLE|$session|0", text)),
                _speechRate.value
            )
            return
        }

        val engine = tts ?: return
        if (!isInitialized) return
        val voice = voiceObjects[voiceName] ?: return
        if (_isPlaying.value) pause()
        session++
        embedded.stop()
        runCatching { engine.voice = voice }
        engine.setSpeechRate(_speechRate.value)
        engine.setPitch(_pitch.value)
        requestAudioFocus()
        isPlayingSample = true
        val id = "$TYPE_SAMPLE|$session|0"
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) {
            finishSample()
        }
    }

    private fun finishSample() {
        isPlayingSample = false
        abandonAudioFocus()
        applyBestVoice()
    }

    // =========================================================================================
    // Lecture
    // =========================================================================================

    fun setSentences(list: List<SentenceSegment>, startIndex: Int = 0, startSpeaking: Boolean = false) {
        val wasPlaying = _isPlaying.value
        haltSpeech(keepPlayingFlag = true)
        sentences = list
        val safeIndex = startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        _currentSentenceIndex.value = safeIndex
        onSentenceChanged?.invoke(safeIndex)
        if (list.isNotEmpty() && canSpeak()) activeEmbedded()?.let { embedded.preload(it) }
        if (startSpeaking && list.isNotEmpty()) {
            play()
        } else if (wasPlaying) {
            pause()
        }
    }

    fun play() {
        if (sentences.isEmpty()) return
        if (!canSpeak()) {
            pendingPlay = true
            return
        }
        isPlayingSample = false
        resumeOnFocusGain = false
        if (!requestAudioFocus()) {
            // Appel en cours ou autre lecture prioritaire : on ne lit pas par-dessus
            pause()
            return
        }
        registerNoisyReceiver()
        speakFrom(_currentSentenceIndex.value)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    private fun speakFrom(start: Int) {
        if (sentences.isEmpty()) return
        val embeddedVoice = activeEmbedded()
        if (embeddedVoice == null && (tts == null || !isInitialized)) return
        session++
        val current = session
        val from = start.coerceIn(0, sentences.lastIndex)
        val to = if (_autoAdvance.value) sentences.lastIndex else from
        lastQueuedIndex = -1

        if (embeddedVoice != null) {
            // Voix intégrée : toute la page est confiée au moteur Piper
            runCatching { tts?.stop() }
            val queue = mutableListOf<EmbeddedSpeechEngine.Utterance>()
            for (i in from..to) {
                val segment = sentences[i]
                val text = segment.speechText.ifBlank { segment.displayText }
                if (text.isBlank()) continue
                val pause = if (i < to) segment.pauseAfterMs else 0
                queue += EmbeddedSpeechEngine.Utterance("$TYPE_SENTENCE|$current|$i", text, pause)
                lastQueuedIndex = i
            }
            if (lastQueuedIndex < 0) {
                onQueueFinished()
                return
            }
            embedded.speak(embeddedVoice, queue, _speechRate.value)
            setPlaying(true)
            return
        }

        val engine = tts ?: return
        embedded.stop()
        engine.setSpeechRate(_speechRate.value)
        engine.setPitch(_pitch.value)
        var first = true
        for (i in from..to) {
            val segment = sentences[i]
            val text = segment.speechText.ifBlank { segment.displayText }
            if (text.isBlank()) continue
            val id = "$TYPE_SENTENCE|$current|$i"
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
            val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(text, mode, params, id) == TextToSpeech.ERROR) {
                handleError(id, TextToSpeech.ERROR)
                return
            }
            first = false
            lastQueuedIndex = i
            if (segment.pauseAfterMs > 0 && i < to) {
                engine.playSilentUtterance(segment.pauseAfterMs.toLong(), TextToSpeech.QUEUE_ADD, "$TYPE_PAUSE|$current|$i")
            }
        }
        if (lastQueuedIndex < 0) {
            onQueueFinished()
            return
        }
        setPlaying(true)
    }

    private fun restartIfPlaying() {
        if (_isPlaying.value && !isPlayingSample && canSpeak()) speakFrom(_currentSentenceIndex.value)
    }

    fun pause() {
        resumeOnFocusGain = false
        pendingPlay = false
        haltSpeech(keepPlayingFlag = false)
        abandonAudioFocus()
        unregisterNoisyReceiver()
    }

    /** Arrête la voix pendant le chargement d'une page sans changer l'état « en lecture ». */
    fun haltForPageChange() = haltSpeech(keepPlayingFlag = true)

    /** Fin du document. */
    fun finishPlayback() = pause()

    fun stop() {
        pause()
        _currentSentenceIndex.value = 0
    }

    private fun haltSpeech(keepPlayingFlag: Boolean) {
        session++
        runCatching { tts?.stop() }
        embedded.stop()
        if (!keepPlayingFlag) setPlaying(false)
    }

    fun jumpToSentence(index: Int, autoPlay: Boolean = true) {
        if (sentences.isEmpty()) return
        val safeIndex = index.coerceIn(0, sentences.lastIndex)
        haltSpeech(keepPlayingFlag = autoPlay)
        _currentSentenceIndex.value = safeIndex
        onSentenceChanged?.invoke(safeIndex)
        if (autoPlay) play() else pause()
    }

    fun nextSentence() {
        if (sentences.isEmpty()) return
        val next = _currentSentenceIndex.value + 1
        if (next < sentences.size) {
            jumpToSentence(next, autoPlay = _isPlaying.value)
        } else {
            val wasPlaying = _isPlaying.value
            haltSpeech(keepPlayingFlag = wasPlaying)
            onPageAdvanceRequested?.invoke(wasPlaying) ?: pause()
        }
    }

    fun previousSentence() {
        if (sentences.isEmpty()) return
        jumpToSentence((_currentSentenceIndex.value - 1).coerceAtLeast(0), autoPlay = _isPlaying.value)
    }

    private fun onQueueFinished() {
        if (!_autoAdvance.value) {
            pause()
            return
        }
        val callback = onPageAdvanceRequested
        if (callback != null) callback(true) else pause()
    }

    private fun parseId(id: String?): Triple<String, Int, Int>? {
        val parts = id?.split('|') ?: return null
        if (parts.size != 3) return null
        val s = parts[1].toIntOrNull() ?: return null
        val i = parts[2].toIntOrNull() ?: return null
        return Triple(parts[0], s, i)
    }

    private fun handleStart(id: String?) {
        val (type, s, index) = parseId(id) ?: return
        if (s != session || type != TYPE_SENTENCE) return
        if (index != _currentSentenceIndex.value) {
            _currentSentenceIndex.value = index
            onSentenceChanged?.invoke(index)
        }
    }

    private fun handleDone(id: String?) {
        val (type, s, index) = parseId(id) ?: return
        if (s != session) return
        when (type) {
            TYPE_SAMPLE -> finishSample()
            TYPE_SENTENCE -> if (index == lastQueuedIndex) onQueueFinished()
        }
    }

    /**
     * Échec d'une voix intégrée : on l'écarte pour cette session, on bascule sur une voix
     * du téléphone et on prévient l'utilisateur (jamais d'échec silencieux).
     */
    private fun handleEmbeddedError(id: String?, reason: String) {
        val parsed = parseId(id)
        if (parsed != null && parsed.second != session) return
        Log.e(TAG, "Embedded voice error for $id: $reason")
        val failing = EmbeddedVoices.byId(_activeVoiceName.value)?.id
        if (parsed?.first == TYPE_SAMPLE) {
            finishSample()
        } else {
            pause()
            failing?.let { failedVoices.add(it) }
            applyBestVoice()
        }
        onEmbeddedVoiceError?.invoke(reason)
    }

    private fun handleError(id: String?, errorCode: Int) {
        val parsed = parseId(id)
        if (parsed != null && parsed.second != session) return
        Log.e(TAG, "TTS error $errorCode for $id")
        if (parsed?.first == TYPE_SAMPLE) {
            finishSample()
            return
        }
        pause()
        // La voix est probablement indisponible : on bascule sur une autre voix de la même langue
        _activeVoiceName.value?.let { failing ->
            failedVoices.add(failing)
            applyBestVoice()
            if (_activeVoiceName.value != failing) onVoiceFallback?.invoke()
        }
    }

    private fun setPlaying(playing: Boolean) {
        if (_isPlaying.value == playing) return
        _isPlaying.value = playing
        onPlaybackStateChanged?.invoke(playing)
    }

    // =========================================================================================
    // Réglages
    // =========================================================================================

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.5f)
        if (clamped == _speechRate.value) return
        _speechRate.value = clamped
        prefs.speechRate = clamped
        restartIfPlaying()
    }

    fun setPitch(value: Float) {
        val clamped = value.coerceIn(0.5f, 2.0f)
        if (clamped == _pitch.value) return
        _pitch.value = clamped
        prefs.pitch = clamped
        // Sans effet sur les voix intégrées : inutile de relancer la phrase
        if (activeEmbedded() == null) restartIfPlaying()
    }

    fun setAutoAdvance(enabled: Boolean) {
        _autoAdvance.value = enabled
        prefs.autoAdvance = enabled
        restartIfPlaying()
    }

    fun setLoopMode(enabled: Boolean) {
        _isLoopModeEnabled.value = enabled
        prefs.loopMode = enabled
    }

    fun getCurrentSentenceText(): String {
        if (sentences.isEmpty()) return ""
        return sentences[_currentSentenceIndex.value.coerceIn(0, sentences.lastIndex)].displayText
    }

    // =========================================================================================
    // Audio : focus, casque, mode silencieux
    // =========================================================================================

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
    }

    @SuppressLint("InlinedApi")
    fun isHeadsetConnected(): Boolean {
        val headsetTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }
        }.getOrDefault(false)
    }

    /** Téléphone en silencieux / vibreur / Ne pas déranger, et aucun casque branché. */
    fun isSilentWithoutHeadset(): Boolean {
        val ringerSilent = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val dnd = runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        return (ringerSilent || dnd) && !isHeadsetConnected()
    }

    /** Libère la mémoire de la voix intégrée (app en arrière-plan sans lecture). */
    fun releaseEmbeddedModel() {
        if (!_isPlaying.value) embedded.releaseModel()
    }

    fun shutdown() {
        pause()
        embedded.shutdown()
        runCatching { tts?.shutdown() }
        tts = null
        isInitialized = false
    }
}
