package com.mrchess.core

import com.mrchess.core.engine.EngineStrength
import com.mrchess.core.engine.SearchLimit
import com.mrchess.core.engine.UciEngine
import com.mrchess.core.explain.Explanation
import com.mrchess.core.explain.MoveExplainer
import com.mrchess.core.review.GameReview
import com.mrchess.core.review.GameReviewer
import com.mrchess.core.rules.Game
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Position

/**
 * High-level facade used by the app: the opponent plays at the chosen strength, while hints and
 * analysis always use full strength so that advice is the best the engine can find.
 */
class Coach(
    private val engine: UciEngine,
    private val threads: Int = 2,
    private val hashMb: Int = 64,
) {

    private var currentStrength: EngineStrength? = null

    /** Call after changing engine options elsewhere (e.g. game review) so the next search reconfigures. */
    fun invalidate() { currentStrength = null }

    suspend fun opponentMove(pos: Position, strength: EngineStrength): Move? {
        if (currentStrength != strength) {
            engine.configure(strength, threads, hashMb)
            currentStrength = strength
        }
        val r = engine.search(pos.toFen(), SearchLimit.MoveTime(strength.moveTimeMs))
        return r.bestMove?.let { Move.fromUci(it) }?.takeIf { pos.isLegal(it) }
    }

    /** Full-strength best move with explanation, comparing against the second-best line. */
    suspend fun hint(pos: Position, limit: SearchLimit = SearchLimit.MoveTime(1500)): Explanation? {
        if (currentStrength != EngineStrength.FULL) {
            engine.configure(EngineStrength.FULL, threads, hashMb)
            currentStrength = EngineStrength.FULL
        }
        if (pos.legalMoves().isEmpty()) return null
        val r = engine.search(pos.toFen(), limit, multiPv = 2)
        return MoveExplainer.explain(pos, r)
    }

    /** Post-game review at full strength. */
    suspend fun review(
        game: Game,
        limit: SearchLimit = SearchLimit.Depth(14),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): GameReview {
        if (currentStrength != EngineStrength.FULL) {
            engine.configure(EngineStrength.FULL, threads, hashMb)
            currentStrength = EngineStrength.FULL
        }
        return GameReviewer(engine, limit).review(game, onProgress)
    }

    fun stop() = engine.stop()
}
