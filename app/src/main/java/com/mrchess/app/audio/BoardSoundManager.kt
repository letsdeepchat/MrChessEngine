package com.mrchess.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mrchess.app.R
import com.mrchess.core.rules.MoveNode

/** Board sound profiles. [id] is persisted, so never rename it. */
enum class SoundPack(val id: String, val label: String, val sounds: IntArray?) {
    STANDARD("standard", "Standard", intArrayOf(R.raw.sfx_standard_move, R.raw.sfx_standard_capture, R.raw.sfx_standard_castle, R.raw.sfx_standard_check, R.raw.sfx_standard_game_end)),
    WOOD("wood", "Wood", intArrayOf(R.raw.sfx_wood_move, R.raw.sfx_wood_capture, R.raw.sfx_wood_castle, R.raw.sfx_wood_check, R.raw.sfx_wood_game_end)),
    FUTURISTIC("futuristic", "Futuristic", intArrayOf(R.raw.sfx_futuristic_move, R.raw.sfx_futuristic_capture, R.raw.sfx_futuristic_castle, R.raw.sfx_futuristic_check, R.raw.sfx_futuristic_game_end)),
    SOFT("soft", "Soft / Classic", intArrayOf(R.raw.sfx_soft_move, R.raw.sfx_soft_capture, R.raw.sfx_soft_castle, R.raw.sfx_soft_check, R.raw.sfx_soft_game_end)),
    ARCADE("arcade", "Arcade", intArrayOf(R.raw.sfx_arcade_move, R.raw.sfx_arcade_capture, R.raw.sfx_arcade_castle, R.raw.sfx_arcade_check, R.raw.sfx_arcade_game_end)),
    MUTED("muted", "Muted (Off)", null);

    companion object {
        val DEFAULT = STANDARD
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Board sound effects through one SoundPool. SoundPool never requests audio focus, so a click on
 * every move does not duck or interrupt music/TTS. Only the selected pack is loaded.
 */
class BoardSoundManager(private val context: Context) {
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

    private var loaded: SoundPack? = null
    private var ids: IntArray = IntArray(0)
    private val ready = HashSet<Int>()
    private var pendingPreview: Int? = null

    init {
        pool.setOnLoadCompleteListener { p, sampleId, status ->
            if (status == 0) ready += sampleId
            if (sampleId == pendingPreview) { pendingPreview = null; p.play(sampleId, 0.9f, 0.9f, 1, 0, 1f) }
        }
    }

    var pack: SoundPack = SoundPack.DEFAULT
        set(value) {
            field = value
            if (value != loaded) load(value)
        }

    private fun load(p: SoundPack) {
        ids.forEach { pool.unload(it) }
        ready.clear()
        ids = p.sounds?.map { pool.load(context, it, 1) }?.toIntArray() ?: IntArray(0)
        loaded = p
    }

    init { load(SoundPack.DEFAULT) }

    fun play(s: Sfx) {
        if (pack == SoundPack.MUTED) return
        ids.getOrNull(s.ordinal)?.takeIf { it in ready }?.let { pool.play(it, 0.9f, 0.9f, 1, 0, 1f) }
    }

    /** Plays a capture in [p] (used by the pack picker); waits for it to load if needed. */
    fun preview(p: SoundPack) {
        pack = p
        val id = ids.getOrNull(Sfx.CAPTURE.ordinal) ?: return
        if (id in ready) pool.play(id, 0.9f, 0.9f, 1, 0, 1f) else pendingPreview = id
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
