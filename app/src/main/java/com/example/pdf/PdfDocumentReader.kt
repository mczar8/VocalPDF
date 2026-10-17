package com.example.pdf

import android.content.Context
import android.net.Uri
import com.example.text.PageTextProcessor
import com.example.text.SentenceSegment
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

data class ExtractedPage(
    val pageNumber: Int, // 1-indexed
    val sentences: List<SentenceSegment>,
    val language: String?
)

/**
 * Lecteur PDF : le document est chargé UNE seule fois (l'ancienne version rechargeait
 * tout le fichier à chaque changement de page), le texte des pages est mis en cache
 * et la page suivante est préparée en arrière-plan pour un enchaînement sans attente.
 */
class PdfDocumentReader private constructor(private val document: PDDocument) {

    val pageCount: Int = document.numberOfPages

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false

    private val rawCache = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > CACHE_SIZE
    }

    private suspend fun rawText(page: Int): String = mutex.withLock {
        check(!closed) { "Document closed" }
        rawCache[page] ?: run {
            val stripper = PDFTextStripper().apply {
                startPage = page
                endPage = page
                sortByPosition = true
            }
            stripper.getText(document).orEmpty().also { rawCache[page] = it }
        }
    }

    /**
     * Extrait et analyse une page. Les pages voisines servent à repérer les en-têtes
     * et pieds de page répétés.
     */
    suspend fun page(pageNumber: Int, languageHint: String?): ExtractedPage = withContext(Dispatchers.IO) {
        val target = pageNumber.coerceIn(1, pageCount.coerceAtLeast(1))
        val raw = rawText(target)
        val neighbors = listOf(target - 1, target + 1)
            .filter { it in 1..pageCount }
            .map { runCatching { rawText(it) }.getOrDefault("") }
        val result = PageTextProcessor.process(raw, neighbors, languageHint)
        ExtractedPage(target, result.segments, result.language)
    }

    /** Prépare une page à l'avance (silencieux en cas d'erreur). */
    fun prefetch(pageNumber: Int) {
        if (pageNumber !in 1..pageCount) return
        scope.launch { runCatching { rawText(pageNumber) } }
    }

    fun close() {
        scope.launch {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    rawCache.clear()
                    runCatching { document.close() }
                }
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    companion object {
        private const val CACHE_SIZE = 12

        suspend fun open(context: Context, uri: Uri): PdfDocumentReader = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException(uri.toString())
            val document = input.use { PDDocument.load(it) }
            PdfDocumentReader(document)
        }
    }
}
