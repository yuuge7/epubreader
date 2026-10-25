package com.ebookreader.data.readaloud

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.SpeechSegmenter
import com.ebookreader.domain.model.SpokenSentence
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What read-aloud needs of a book: its chapters' text, on demand. */
interface ReadAloudBook {
    val bookId: Long
    val title: String
    val author: String
    val language: String?
    val chapterCount: Int
    fun chapterTitle(index: Int): String
    /** The chapter's visible text, paragraphs separated by '\n'. */
    suspend fun chapterText(index: Int): String
}

data class ReadAloudState(
    /** Reading aloud has been started and not stopped; it may be paused. */
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val bookId: Long = -1L,
    val title: String = "",
    val author: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val sentence: SpokenSentence? = null,
    val rate: Float = 1f,
    val error: String? = null,
)

/**
 * Reads a book aloud with the device's text-to-speech engine, a sentence at a time, and
 * carries on into the next chapter by itself. One for the whole app, so the speech goes
 * on with the screen off; [ReadAloudService] holds the media notification meanwhile.
 *
 * The engine has no pause: pausing stops it and playing speaks the current sentence
 * again. Sentences are queued one ahead so there is no gap between them. Every utterance
 * carries a generation number, and callbacks from a queue that was since flushed are
 * ignored.
 */
@Singleton
class ReadAloudController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var engineReady: CompletableDeferred<Boolean>? = null
    private var book: ReadAloudBook? = null
    private var sentences: List<SpokenSentence> = emptyList()
    private var index = 0
    private var queuedUpTo = -1
    private var generation = 0

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            scope.launch {
                // A call or another player takes over: stop talking, and stay stopped.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
        }
        .build()

    /**
     * Starts reading [book] at [chapter], from the sentence that begins with [fromText]
     * (what is at the top of the page), or from the chapter's start.
     */
    fun start(book: ReadAloudBook, chapter: Int, fromText: String?, rate: Float) {
        scope.launch {
            if (!ensureEngine()) {
                _state.update { ReadAloudState(error = "No text-to-speech voice is installed on this device") }
                return@launch
            }
            this@ReadAloudController.book = book
            book.language?.let { tag ->
                val locale = Locale.forLanguageTag(tag)
                if ((tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = locale
                }
            }
            tts?.setSpeechRate(rate)
            _state.update {
                ReadAloudState(
                    isActive = true, bookId = book.bookId, title = book.title, author = book.author, rate = rate,
                )
            }
            loadChapter(chapter)
            index = fromText?.let { indexOfText(it, chapterText) } ?: 0
            // A cover or an image page has nothing to say: start at the next one that has.
            if (sentences.isEmpty() && !advanceChapter(+1)) {
                stop()
                _state.update { it.copy(error = "There is no text to read from here") }
                return@launch
            }
            ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
            play()
        }
    }

    fun play() {
        val engine = tts ?: return
        if (book == null || sentences.isEmpty()) return
        audio.requestAudioFocus(focusRequest)
        _state.update { it.copy(isPlaying = true) }
        speakFrom(engine, index)
    }

    fun pause() {
        if (!_state.value.isPlaying) return
        generation++
        tts?.stop()
        _state.update { it.copy(isPlaying = false) }
    }

    fun toggle() = if (_state.value.isPlaying) pause() else play()

    /** A sentence forward or back; past the chapter's ends, into the next or previous one. */
    fun skip(by: Int) {
        scope.launch {
            val target = index + by
            when {
                target in sentences.indices -> index = target
                target >= sentences.size -> if (!advanceChapter(+1)) return@launch
                else -> if (!advanceChapter(-1)) return@launch else index = sentences.lastIndex.coerceAtLeast(0)
            }
            publishSentence()
            if (_state.value.isPlaying) tts?.let { speakFrom(it, index) }
        }
    }

    fun setRate(rate: Float) {
        if (rate == _state.value.rate) return
        tts?.setSpeechRate(rate)
        _state.update { it.copy(rate = rate) }
        // The new pace takes effect from the next utterance; restart this one at it.
        if (_state.value.isPlaying) tts?.let { speakFrom(it, index) }
    }

    fun stop() {
        generation++
        tts?.stop()
        audio.abandonAudioFocusRequest(focusRequest)
        book = null
        sentences = emptyList()
        _state.value = ReadAloudState(rate = _state.value.rate)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ── Engine ───────────────────────────────────────────────────────────────

    private suspend fun ensureEngine(): Boolean {
        engineReady?.let { return it.await() }
        val ready = CompletableDeferred<Boolean>()
        engineReady = ready
        tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }.apply {
            setAudioAttributes(attributes)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { scope.launch { onUtteranceStart(utteranceId) } }
                override fun onDone(utteranceId: String) { scope.launch { onUtteranceDone(utteranceId) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { scope.launch { onUtteranceDone(utteranceId) } }
            })
        }
        val ok = ready.await()
        if (!ok) {
            tts?.shutdown()
            tts = null
            engineReady = null
        }
        return ok
    }

    private fun speakFrom(engine: TextToSpeech, from: Int) {
        generation++
        engine.stop()
        queuedUpTo = -1
        index = from
        enqueue(engine, from, TextToSpeech.QUEUE_FLUSH)
        enqueue(engine, from + 1, TextToSpeech.QUEUE_ADD)
        publishSentence()
    }

    private fun enqueue(engine: TextToSpeech, i: Int, mode: Int) {
        val sentence = sentences.getOrNull(i) ?: return
        engine.speak(sentence.text, mode, Bundle.EMPTY, "$generation:${_state.value.chapterIndex}:$i")
        queuedUpTo = i
    }

    private fun parse(id: String): Int? {
        val parts = id.split(':')
        if (parts.size != 3 || parts[0].toIntOrNull() != generation) return null
        return parts[2].toIntOrNull()
    }

    private fun onUtteranceStart(id: String) {
        val i = parse(id) ?: return
        index = i
        publishSentence()
        tts?.let { if (queuedUpTo < i + 1) enqueue(it, i + 1, TextToSpeech.QUEUE_ADD) }
    }

    private suspend fun onUtteranceDone(id: String) {
        val i = parse(id) ?: return
        if (i < sentences.lastIndex) return
        // The chapter is done: on to the next, or the end of the book.
        if (advanceChapter(+1)) {
            tts?.let { speakFrom(it, 0) }
        } else {
            stop()
        }
    }

    // ── Chapters ─────────────────────────────────────────────────────────────

    /** The open chapter's text, which [sentences] index into. */
    private var chapterText = ""

    private suspend fun loadChapter(chapter: Int) {
        val b = book ?: return
        val text = b.chapterText(chapter)
        show(b, chapter, text, SpeechSegmenter.split(text))
    }

    /**
     * Moves to the next chapter with something to say in it. At the book's end (or its
     * start), false, and nothing has moved: empty cover pages are looked at, not opened.
     */
    private suspend fun advanceChapter(direction: Int): Boolean {
        val b = book ?: return false
        var chapter = _state.value.chapterIndex + direction
        while (chapter in 0 until b.chapterCount) {
            val text = b.chapterText(chapter)
            val split = SpeechSegmenter.split(text)
            if (split.isNotEmpty()) {
                show(b, chapter, text, split)
                return true
            }
            chapter += direction
        }
        return false
    }

    private fun show(b: ReadAloudBook, chapter: Int, text: String, split: List<SpokenSentence>) {
        chapterText = text
        sentences = split
        index = 0
        _state.update { it.copy(chapterIndex = chapter, chapterTitle = b.chapterTitle(chapter), sentence = null) }
    }

    private fun publishSentence() = _state.update { it.copy(sentence = sentences.getOrNull(index)) }

    /**
     * The sentence holding the page's first visible text, found by its words. The page
     * counts its text a little differently (raw whitespace), so its text is matched with
     * whitespace collapsed, by a long prefix first and a shorter one if that misses.
     */
    private fun indexOfText(text: String, chapter: String): Int? {
        val flat = chapter.replace('\n', ' ')
        val probe = SpeechSegmenter.normalize(text)
        val at = listOf(80, 30).firstNotNullOfOrNull { n ->
            probe.take(n).takeIf { it.length >= 3 }?.let { flat.indexOf(it) }?.takeIf { it >= 0 }
        } ?: return null
        return sentences.indexOfLast { it.start <= at }.takeIf { it >= 0 }
    }
}
