package com.mrchess.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.content.Intent
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
    private val _hasNaturalVoice = MutableStateFlow(false)
    /** False when only low-quality voices are installed: the UI then offers "Download natural voice". */
    val hasNaturalVoice: StateFlow<Boolean> = _hasNaturalVoice
    private val _voiceName = MutableStateFlow<String?>(null)
    val voiceName: StateFlow<String?> = _voiceName


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
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        val best = runCatching { pickVoice(tts.voices.orEmpty()) }.getOrNull()
        if (best != null) {
            tts.voice = best
        } else {
            val r = tts.setLanguage(Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale("en", "IN"))
        }
        _hasNaturalVoice.value = best != null && best.quality >= Voice.QUALITY_HIGH
        _voiceName.value = best?.name
        // Speech audio attributes: TTS gets transient focus only while speaking, nothing permanent.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Slightly slower and warmer than default for a conversational pace.
        tts.setSpeechRate(0.95f)
        tts.setPitch(1.02f)
    }

    /** Opens the system screen to install TTS voice data (works offline afterwards). */
    fun installVoiceData(context: Context) {
        val i = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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

        /**
         * Best installed English voice that works offline: highest quality first, then preferred
         * accents (US, India, UK), then names that indicate neural/enhanced voices, then latency.
         */
        fun pickVoice(voices: Collection<Voice>): Voice? = voices
            .filter { v ->
                v.locale.language == "en" && !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
            }
            .filter { it.quality >= Voice.QUALITY_NORMAL }
            .maxByOrNull { v ->
                val accent = when (v.locale.country) { "US" -> 3; "IN" -> 2; "GB" -> 1; else -> 0 }
                val name = v.name.lowercase()
                val neural = listOf("neural", "wavenet", "enhanced", "premium", "natural").any { it in name }
                v.quality * 100 + (if (neural) 50 else 0) + accent * 10 - v.latency
            }

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
