package com.mrchess.app

import android.app.Application
import com.mrchess.app.audio.BoardSoundManager
import com.mrchess.app.audio.ChessVoiceNarrator
import com.mrchess.app.data.EngineProvider
import com.mrchess.app.data.ReviewRepository
import com.mrchess.app.data.SettingsRepository
import com.mrchess.core.review.OpeningBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide singletons: the TTS narrator is initialised at startup so the first readout is instant. */
class MrChessApp : Application() {
    lateinit var narrator: ChessVoiceNarrator
        private set
    lateinit var sounds: BoardSoundManager
        private set
    lateinit var history: ReviewRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    /** Background work that outlives a screen (warm-up). Never touches the network. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        narrator = ChessVoiceNarrator(this)
        sounds = BoardSoundManager(this)
        settings = SettingsRepository(this)
        history = ReviewRepository(this, ReviewRepository.createCloudSync(this))
        // Warm up off the main thread so the first move, first badge and first bot reply are instant:
        // the opening book (move-quality "Book" badges) and Stockfish's threads + hash table.
        appScope.launch { runCatching { OpeningBook.size } }
        appScope.launch { runCatching { EngineProvider.coach(this@MrChessApp).warmUp() } }
    }
}
