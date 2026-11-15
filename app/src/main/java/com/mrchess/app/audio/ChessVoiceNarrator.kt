package com.mrchess.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mrchess.core.explain.Explanation
import com.mrchess.core.review.MoveAnnotation
import com.mrchess.core.rules.Position
import com.mrchess.core.speech.SpeechFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * On-demand voice readout of coach explanations. One TextToSpeech instance for the app's
 * lifetime; nothing is ever spoken automatically, only when the user taps the speaker button.
 * Uses the on-device TTS engine, so it works offline once a voice is installed.
 */
class ChessVoiceNarrator(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private var ready = false

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { if (id == LAST) _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false }
            override fun onStop(id: String?, interrupted: Boolean) { _speaking.value = false }
        })
    }

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val r = tts.setLanguage(Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale("en", "IN"))
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            tts.setSpeechRate(1.0f)
        }
    }

    val isAvailable: Boolean get() = ready

    /** Speaks the parts in order; a second tap while speaking stops. */
    fun toggle(parts: List<String>) {
        if (_speaking.value) { stop(); return }
        if (!ready || parts.isEmpty()) return
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        parts.forEachIndexed { i, p ->
            val id = if (i == parts.lastIndex) LAST else "part$i"
            tts.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
        }
        _speaking.value = true
    }

    fun stop() {
        tts.stop()
        _speaking.value = false
    }

    fun shutdown() = tts.shutdown()

    companion object {
        private const val LAST = "last"

        /** Title, tags, bullet reasons and the expected continuation, in natural English. */
        fun script(e: Explanation, pos: Position): List<String> = buildList {
            val title = SpeechFormatter.san(e.san) ?: e.san
            add("Best move: $title. Evaluation ${SpeechFormatter.eval(e.scoreWhite.display()) ?: e.scoreWhite.display()}.")
            if (e.tags.isNotEmpty()) add(e.tags.joinToString(", ") { it.label } + ".")
            e.reasons.filterNot { it.startsWith("Expected continuation") }.take(3).forEach { add(SpeechFormatter.text(it)) }
            if (e.line.size > 1) add("Expected continuation: " + SpeechFormatter.line(e.line.take(3)) + ".")
            e.alternative?.let { a -> add("Second best: ${SpeechFormatter.san(a.san) ?: a.san}.") }
        }

        fun script(a: MoveAnnotation, playedSan: String): List<String> = buildList {
            add("${SpeechFormatter.san(playedSan) ?: playedSan}. ${a.quality.label}.")
            add(SpeechFormatter.text(a.rationale))
        }
    }
}
