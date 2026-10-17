package com.example.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig
import com.example.text.SpeechChunker
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lecture avec les voix intégrées (Piper via sherpa-onnx), entièrement hors ligne.
 *
 * Fonctionnement :
 *  - un fil « synthèse » charge le modèle et fabrique l'audio, toujours avec un morceau d'avance
 *  - un fil « lecture » joue l'audio avec AudioTrack, sans blanc entre les phrases
 *  - les événements début / fin / erreur reprennent les identifiants du moteur Android,
 *    ce qui permet de partager toute la logique de lecture (surlignage, pages…)
 */
class EmbeddedSpeechEngine(context: Context) {

    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)

        /** @param reason cause technique courte, affichée à l'utilisateur pour le diagnostic */
        fun onError(utteranceId: String, reason: String)
    }

    /** Une phrase à lire, suivie éventuellement d'un silence. */
    data class Utterance(val id: String, val text: String, val silenceAfterMs: Int = 0)

    private data class Piece(
        val utteranceIndex: Int,
        val text: String,
        val isFirst: Boolean,
        val speed: Float,
        /** Pause écrite après le morceau (souffle, virgule, fin de phrase + pause de paragraphe). */
        val pauseAfterMs: Int
    )

    /** Événement à déclencher quand la tête de lecture atteint une position (surlignage synchronisé). */
    private class Marker(val frame: Long, val action: () -> Unit)

    private enum class WriteResult { OK, CANCELLED, FAILED }

    private class ModelLoadException(cause: Throwable) : Exception(cause)

    private val appContext = context.applicationContext
    private val synthExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "vocalpdf-synth") }
    private val playExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            r.run()
        }, "vocalpdf-play")
    }
    private val generation = AtomicInteger(0)

    /** Dernière cause d'échec (fil de lecture), pour un message compréhensible. */
    @Volatile
    private var lastError: String? = null
    private val trackLock = Any()

    var listener: Listener? = null

    // Accédés uniquement depuis le fil de synthèse
    private var tts: OfflineTts? = null
    private var loadedVoiceId: String? = null

    // Écrit sous trackLock ; utilisé par le fil de lecture
    private var track: AudioTrack? = null

    /** Charge le modèle à l'avance (la première phrase démarre alors sans attente). */
    fun preload(voice: EmbeddedVoice) {
        runCatching {
            synthExecutor.execute { runCatching { ensureLoaded(voice) }.onFailure { Log.w(TAG, "Preload failed", it) } }
        }
    }

    /** Libère la mémoire du modèle (≈ 100 Mo) quand l'app passe en arrière-plan sans lecture. */
    fun releaseModel() {
        runCatching {
            synthExecutor.execute {
                runCatching { tts?.release() }
                tts = null
                loadedVoiceId = null
            }
        }
    }

    /** Lit une file de phrases (remplace la lecture en cours). */
    fun speak(voice: EmbeddedVoice, utterances: List<Utterance>, speed: Float) {
        val gen = generation.incrementAndGet()
        haltTrack()
        if (utterances.isEmpty()) return
        runCatching { playExecutor.execute { runQueue(gen, voice, utterances, speed) } }
    }

    /** Arrête immédiatement la lecture. */
    fun stop() {
        generation.incrementAndGet()
        haltTrack()
    }

    fun shutdown() {
        stop()
        runCatching { playExecutor.execute { releaseTrack() } }
        runCatching {
            synthExecutor.execute {
                runCatching { tts?.release() }
                tts = null
                loadedVoiceId = null
            }
        }
        playExecutor.shutdown()
        synthExecutor.shutdown()
    }

    // ---------------------------------------------------------------------------------------
    // Synthèse (fil « synth » uniquement)
    // ---------------------------------------------------------------------------------------

    private fun ensureLoaded(voice: EmbeddedVoice): OfflineTts {
        tts?.let { if (loadedVoiceId == voice.id) return it }
        runCatching { tts?.release() }
        tts = null
        loadedVoiceId = null

        val espeakDir = EspeakDataInstaller.ensureInstalled(appContext)
        Log.i(TAG, "espeak-ng data ready: ${espeakDir.absolutePath} (${espeakDir.list()?.size ?: 0} entries)")
        val vitsConfig = OfflineTtsVitsModelConfig().apply {
            model = voice.modelAsset
            tokens = voice.tokensAsset
            dataDir = espeakDir.absolutePath
        }
        val modelConfig = OfflineTtsModelConfig().apply {
            vits = vitsConfig
            numThreads = threadCount()
            debug = false
            provider = "cpu"
        }
        val config = OfflineTtsConfig().apply {
            model = modelConfig
            maxNumSentences = 1
        }
        val start = SystemClock.elapsedRealtime()
        val engine = OfflineTts(appContext.assets, config)
        Log.i(TAG, "Voice ${voice.id} loaded in ${SystemClock.elapsedRealtime() - start} ms")
        tts = engine
        loadedVoiceId = voice.id
        return engine
    }

    private fun threadCount(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    private fun synthesize(gen: Int, voice: EmbeddedVoice, text: String, speed: Float): GeneratedAudio? {
        if (gen != generation.get()) return null
        val engine = try {
            ensureLoaded(voice)
        } catch (t: Throwable) {
            throw ModelLoadException(t)
        }
        val audio = engine.generate(text, 0, speed)
        if (audio.samples.isEmpty()) Log.w(TAG, "Empty audio for: ${text.take(60)}")
        return audio
    }

    // ---------------------------------------------------------------------------------------
    // Lecture (fil « play » uniquement)
    // ---------------------------------------------------------------------------------------

    private fun runQueue(gen: Int, voice: EmbeddedVoice, utterances: List<Utterance>, speed: Float) {
        // Groupes de souffle : débit régulier, pauses naturelles et maîtrisées
        val pieces = utterances.flatMapIndexed { index, u ->
            val chunks = SpeechChunker.split(u.text)
                .ifEmpty { listOf(SpeechChunker.Chunk(u.text, SpeechChunker.Pause.END)) }
            chunks.mapIndexed { i, chunk ->
                val pace = if (voice.compensateLongPassages) SpeechChunker.paceFactor(chunk.text) else 1f
                Piece(
                    utteranceIndex = index,
                    text = chunk.text,
                    isFirst = i == 0,
                    speed = speed * voice.baseSpeed * pace,
                    pauseAfterMs = pauseFor(chunk.pause) + if (i == chunks.lastIndex) u.silenceAfterMs else 0
                )
            }
        }

        fun submit(i: Int): Future<GeneratedAudio?> =
            synthExecutor.submit<GeneratedAudio?> { synthesize(gen, voice, pieces[i].text, pieces[i].speed) }

        val markers = ArrayDeque<Marker>()
        var framesWritten = 0L
        var currentUtterance = -1
        var t: AudioTrack? = null

        fun fail(reason: String) {
            Log.e(TAG, "Embedded voice failed: $reason")
            if (gen == generation.get()) {
                listener?.onError(utterances[currentUtterance.coerceAtLeast(0)].id, reason)
            }
        }

        try {
            releaseTrack()
            var next: Future<GeneratedAudio?>? = submit(0)
            for (i in pieces.indices) {
                val piece = pieces[i]
                val audio = next?.let { awaitAudio(gen, it, t, markers) }
                if (gen != generation.get()) return
                next = if (i + 1 < pieces.size) submit(i + 1) else null

                // Début d'une nouvelle phrase : déclenché quand la voix l'atteint réellement
                if (piece.isFirst) {
                    val previous = currentUtterance
                    val index = piece.utteranceIndex
                    currentUtterance = index
                    markers.addLast(Marker(framesWritten) {
                        if (previous >= 0) listener?.onDone(utterances[previous].id)
                        listener?.onStart(utterances[index].id)
                    })
                }

                // Morceau sans son (symboles, emoji…) : on passe simplement au suivant.
                // Les silences ajoutés par la voix au début et à la fin sont retirés : c'est nous
                // qui posons des pauses régulières.
                val samples = audio?.samples?.let { trimSilence(it, audio.sampleRate) }
                if (audio == null || samples == null || samples.isEmpty()) {
                    if (t != null) dispatchMarkers(t, markers) else runAllMarkers(markers)
                    continue
                }

                val currentTrack = t ?: obtainTrack(audio.sampleRate)?.also { t = it }
                if (currentTrack == null) {
                    fail(lastError ?: "audio")
                    return
                }

                when (writeAll(gen, currentTrack, samples, markers)) {
                    WriteResult.OK -> framesWritten += samples.size
                    WriteResult.CANCELLED -> return
                    WriteResult.FAILED -> { fail(lastError ?: "audio write"); return }
                }
                if (piece.pauseAfterMs > 0) {
                    val silence = FloatArray(audio.sampleRate * piece.pauseAfterMs / 1000)
                    when (writeAll(gen, currentTrack, silence, markers)) {
                        WriteResult.OK -> framesWritten += silence.size
                        WriteResult.CANCELLED -> return
                        WriteResult.FAILED -> { fail(lastError ?: "audio write"); return }
                    }
                }
            }

            // Fin de la file : on pousse un peu de silence pour que le dernier mot soit joué en entier
            val endTrack = t
            if (endTrack != null) {
                val padding = FloatArray(endTrack.bufferSizeInFrames + endTrack.sampleRate / 10)
                when (writeAll(gen, endTrack, padding, markers)) {
                    WriteResult.OK -> Unit
                    WriteResult.CANCELLED -> return
                    WriteResult.FAILED -> { fail(lastError ?: "audio write"); return }
                }
                if (!waitUntilPlayed(gen, endTrack, framesWritten, markers)) return
            }
            runAllMarkers(markers)
            if (gen == generation.get() && currentUtterance >= 0) {
                listener?.onDone(utterances[currentUtterance].id)
            }
        } catch (e: ExecutionException) {
            val cause = e.cause
            Log.e(TAG, "Embedded synthesis failed", cause)
            val root = (cause as? ModelLoadException)?.cause ?: cause
            fail("${if (cause is ModelLoadException) "model" else "synthesis"}: ${root?.message ?: root?.javaClass?.simpleName}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Embedded playback failed", e)
            fail("playback: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Not enough memory for the embedded voice", e)
            fail("memory")
        } finally {
            releaseTrack()
        }
    }

    private fun obtainTrack(sampleRate: Int): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // La taille du tampon DOIT être un multiple de la taille d'une trame (4 octets en float mono),
        // sinon Android refuse de créer la sortie audio. Calcul en trames, puis conversion en octets.
        val minFrames = minBuffer.coerceAtLeast(BYTES_PER_FRAME) / BYTES_PER_FRAME
        val bufferFrames = maxOf(minFrames * 2, sampleRate / 4) // ≥ 250 ms
        lastError = null
        // Construit hors verrou (appel au serveur audio), puis publié sous verrou
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferFrames * BYTES_PER_FRAME)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.onFailure {
            Log.e(TAG, "AudioTrack creation failed (rate=$sampleRate, frames=$bufferFrames)", it)
            lastError = "audio: ${it.message ?: it.javaClass.simpleName}"
        }.getOrNull() ?: return null

        val started = runCatching { created.play() }
            .onFailure { lastError = "audio play: ${it.message ?: it.javaClass.simpleName}" }
            .isSuccess
        if (!started) {
            runCatching { created.release() }
            return null
        }
        Log.i(TAG, "AudioTrack ready: $sampleRate Hz, buffer $bufferFrames frames")
        synchronized(trackLock) { track = created }
        return created
    }

    /** Attend l'audio du morceau suivant en continuant à synchroniser le surlignage. */
    private fun awaitAudio(gen: Int, future: Future<GeneratedAudio?>, t: AudioTrack?, markers: ArrayDeque<Marker>): GeneratedAudio? {
        while (true) {
            if (gen != generation.get()) return null
            try {
                return future.get(WAIT_STEP_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (t != null) dispatchMarkers(t, markers)
            }
        }
    }

    /** Écriture non bloquante par petits blocs : arrêt immédiat possible, surlignage synchronisé. */
    private fun writeAll(gen: Int, t: AudioTrack, samples: FloatArray, markers: ArrayDeque<Marker>): WriteResult {
        var offset = 0
        var stalledSince = 0L
        var stalledHead = -1L
        while (offset < samples.size) {
            if (gen != generation.get()) return WriteResult.CANCELLED
            dispatchMarkers(t, markers)
            val count = minOf(CHUNK_FRAMES, samples.size - offset)
            val written = t.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
            when {
                written < 0 -> {
                    lastError = "audio write $written"
                    return if (gen != generation.get()) WriteResult.CANCELLED else WriteResult.FAILED
                }
                written == 0 -> {
                    // Sortie audio figée (la tête de lecture n'avance plus) : on abandonne
                    val head = headPosition(t)
                    val now = SystemClock.elapsedRealtime()
                    if (head != stalledHead) {
                        stalledHead = head
                        stalledSince = now
                    } else if (now - stalledSince > STALL_TIMEOUT_MS) {
                        lastError = "audio output stalled"
                        return if (gen != generation.get()) WriteResult.CANCELLED else WriteResult.FAILED
                    }
                    SystemClock.sleep(WAIT_STEP_MS)
                }
                else -> {
                    offset += written
                    stalledHead = -1L
                }
            }
        }
        return if (gen == generation.get()) WriteResult.OK else WriteResult.CANCELLED
    }

    /** Attend que l'audio utile (sans le silence final) ait réellement été entendu. */
    private fun waitUntilPlayed(gen: Int, t: AudioTrack, targetFrames: Long, markers: ArrayDeque<Marker>): Boolean {
        val startHead = headPosition(t)
        val remainingMs = ((targetFrames - startHead).coerceAtLeast(0) * 1000L) / t.sampleRate
        val deadline = SystemClock.elapsedRealtime() + remainingMs + 1_500L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (gen != generation.get()) return false
            dispatchMarkers(t, markers)
            if (headPosition(t) >= targetFrames) return true
            SystemClock.sleep(WAIT_STEP_MS)
        }
        return gen == generation.get()
    }

    private fun pauseFor(pause: SpeechChunker.Pause): Int = when (pause) {
        SpeechChunker.Pause.BREATH -> PAUSE_BREATH_MS
        SpeechChunker.Pause.COMMA -> PAUSE_COMMA_MS
        SpeechChunker.Pause.END -> PAUSE_SENTENCE_MS
    }

    /** Retire le silence de début et de fin (≈ 0,2 s chacun chez Piper), en gardant une petite marge. */
    private fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        var first = 0
        while (first < samples.size && kotlin.math.abs(samples[first]) < SILENCE_THRESHOLD) first++
        if (first == samples.size) return FloatArray(0)
        var last = samples.lastIndex
        while (last > first && kotlin.math.abs(samples[last]) < SILENCE_THRESHOLD) last--
        val margin = sampleRate * TRIM_MARGIN_MS / 1000
        val from = (first - margin).coerceAtLeast(0)
        val to = (last + 1 + margin).coerceAtMost(samples.size)
        return if (from == 0 && to == samples.size) samples else samples.copyOfRange(from, to)
    }

    private fun headPosition(t: AudioTrack): Long =
        runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(Long.MAX_VALUE)

    private fun dispatchMarkers(t: AudioTrack, markers: ArrayDeque<Marker>) {
        if (markers.isEmpty()) return
        val head = headPosition(t)
        while (markers.isNotEmpty() && markers.first().frame <= head) markers.removeFirst().action()
    }

    private fun runAllMarkers(markers: ArrayDeque<Marker>) {
        while (markers.isNotEmpty()) markers.removeFirst().action()
    }

    private fun haltTrack() = synchronized(trackLock) {
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
            }
        }
    }

    private fun releaseTrack() = synchronized(trackLock) {
        track?.let { runCatching { it.release() } }
        track = null
    }

    companion object {
        private const val TAG = "EmbeddedTts"
        private const val CHUNK_FRAMES = 2048
        private const val WAIT_STEP_MS = 8L
        private const val BYTES_PER_FRAME = 4 // float 32 bits, mono

        // Pauses entre morceaux (réglées à l'écoute, comme un lecteur humain)
        private const val PAUSE_BREATH_MS = 120
        private const val PAUSE_COMMA_MS = 200
        private const val PAUSE_SENTENCE_MS = 320
        private const val SILENCE_THRESHOLD = 0.02f // ≈ −34 dB
        private const val TRIM_MARGIN_MS = 40
        private const val STALL_TIMEOUT_MS = 2_000L
    }
}

/**
 * La bibliothèque de prononciation espeak-ng doit être lue depuis un vrai dossier :
 * elle est copiée une fois depuis les assets vers le stockage interne de l'app.
 */
internal object EspeakDataInstaller {
    // Recopie automatique à chaque nouvelle version de l'app
    private val VERSION = "espeak-1.13.8-fr-en-" + BuildConfig.VERSION_CODE

    @Synchronized
    fun ensureInstalled(context: Context): File {
        val target = File(context.filesDir, EmbeddedVoices.ESPEAK_ASSET_DIR)
        val marker = File(target, ".installed")
        if (marker.exists() && marker.readText() == VERSION) return target
        target.deleteRecursively()
        copyAssetDir(context, EmbeddedVoices.ESPEAK_ASSET_DIR, target)
        marker.writeText(VERSION)
        return target
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetDir(context, "$assetPath/$child", File(target, child))
    }
}
