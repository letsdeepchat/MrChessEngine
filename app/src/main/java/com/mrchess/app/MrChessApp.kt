package com.mrchess.app

import android.app.Application
import com.mrchess.app.audio.BoardSoundManager
import com.mrchess.app.audio.ChessVoiceNarrator
import com.mrchess.app.data.ReviewRepository
import com.mrchess.app.data.SettingsRepository

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

    override fun onCreate() {
        super.onCreate()
        narrator = ChessVoiceNarrator(this)
        sounds = BoardSoundManager(this)
        settings = SettingsRepository(this)
        history = ReviewRepository(this, ReviewRepository.createCloudSync(this))
    }
}
