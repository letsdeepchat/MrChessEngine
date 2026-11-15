package com.mrchess.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mrchess.app.R
import com.mrchess.core.rules.MoveNode

/**
 * Board sound effects through one pre-loaded SoundPool. SoundPool never requests audio focus,
 * so playing a click on every move does not duck or interrupt music/TTS (the cause of the old
 * "volume fluctuation"). Two streams are enough: a move sound can overlap the previous one.
 */
class BoardSoundManager(context: Context) {
    enum class Sfx { MOVE, CAPTURE, CASTLE, CHECK, GAME_END }

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids = mapOf(
        Sfx.MOVE to pool.load(context, R.raw.move, 1),
        Sfx.CAPTURE to pool.load(context, R.raw.capture, 1),
        Sfx.CASTLE to pool.load(context, R.raw.castle, 1),
        Sfx.CHECK to pool.load(context, R.raw.check, 1),
        Sfx.GAME_END to pool.load(context, R.raw.game_end, 1),
    )

    var enabled = true

    fun play(s: Sfx) {
        if (!enabled) return
        ids[s]?.let { pool.play(it, 0.9f, 0.9f, 1, 0, 1f) }
    }

    /** Picks the right sound for a played move. */
    fun playFor(node: MoveNode, gameOver: Boolean) = play(
        when {
            gameOver -> Sfx.GAME_END
            node.san.endsWith("+") || node.san.endsWith("#") -> Sfx.CHECK
            node.isCastle -> Sfx.CASTLE
            node.capturedPiece != null -> Sfx.CAPTURE
            else -> Sfx.MOVE
        },
    )

    fun release() = pool.release()
}
