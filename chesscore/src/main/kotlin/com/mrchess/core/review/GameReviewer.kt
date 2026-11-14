package com.mrchess.core.review

import com.mrchess.core.engine.Score
import com.mrchess.core.engine.SearchLimit
import com.mrchess.core.engine.SearchResult
import com.mrchess.core.engine.UciEngine
import com.mrchess.core.explain.Explanation
import com.mrchess.core.explain.MoveExplainer
import com.mrchess.core.rules.Game
import com.mrchess.core.rules.Side
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.exp

enum class MoveClass(val label: String, val symbol: String) {
    BEST("Best", "★"),
    EXCELLENT("Excellent", "!"),
    GOOD("Good", ""),
    INACCURACY("Inaccuracy", "?!"),
    MISTAKE("Mistake", "?"),
    BLUNDER("Blunder", "??"),
    BOOK("Forced", ""),
}

data class ReviewedMove(
    val ply: Int,
    val side: Side,
    val san: String,
    val classification: MoveClass,
    /** Eval before and after the move, White's point of view. */
    val evalBefore: Score,
    val evalAfter: Score,
    /** Drop in the mover's winning chances, on a 0..100 scale. */
    val winPercentLoss: Double,
    val cpLoss: Int,
    /** Engine's recommendation and why; null when the played move was the best. */
    val best: Explanation?,
    /** Why the played move was a problem: the refutation the opponent now has. */
    val refutation: Explanation?,
    val comment: String,
)

data class GameReview(
    val moves: List<ReviewedMove>,
    val accuracyWhite: Double,
    val accuracyBlack: Double,
) {
    fun count(side: Side, c: MoveClass) = moves.count { it.side == side && it.classification == c }
}

/**
 * Post-game review in the style of Lichess: win-percentage loss per move, classification
 * thresholds of 10/20/30 points (inaccuracy/mistake/blunder) and the Lichess accuracy formula.
 */
class GameReviewer(
    private val engine: UciEngine,
    private val limit: SearchLimit = SearchLimit.Depth(16),
) {
    suspend fun review(game: Game, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): GameReview {
        val positions = game.positions
        val analyses = ArrayList<SearchResult>(positions.size)
        for ((i, p) in positions.withIndex()) {
            coroutineContext.ensureActive()
            val multiPv = if (i < positions.lastIndex) 2 else 1
            analyses += if (p.legalMoves().isEmpty()) SearchResult(null, null, emptyList())
            else engine.search(p.toFen(), limit, multiPv)
            onProgress(i + 1, positions.size)
        }

        val reviewed = ArrayList<ReviewedMove>()
        for (ply in game.moves.indices) {
            val pos = positions[ply]
            val next = positions[ply + 1]
            val mover = pos.sideToMove
            val before = whiteScore(analyses[ply], pos.sideToMove, terminalFor = next)
            val after = whiteScore(analyses[ply + 1], next.sideToMove, terminalFor = next)

            val moverBefore = if (mover == Side.WHITE) before else before.negate()
            val moverAfter = if (mover == Side.WHITE) after else after.negate()
            val wpLoss = ((winPct(moverBefore) - winPct(moverAfter))).coerceAtLeast(0.0)
            val cpLoss = MoveExplainer.cpDelta(moverBefore, moverAfter)

            val played = game.moves[ply].uci
            val bestUci = analyses[ply].bestMove
            val onlyOneLegal = pos.legalMoves().size == 1
            val cls = when {
                onlyOneLegal -> MoveClass.BOOK
                played == bestUci -> MoveClass.BEST
                wpLoss >= 30 -> MoveClass.BLUNDER
                wpLoss >= 20 -> MoveClass.MISTAKE
                wpLoss >= 10 -> MoveClass.INACCURACY
                wpLoss < 2 -> MoveClass.EXCELLENT
                else -> MoveClass.GOOD
            }
            val needsHelp = cls == MoveClass.INACCURACY || cls == MoveClass.MISTAKE || cls == MoveClass.BLUNDER
            val bestExpl = if (needsHelp) MoveExplainer.explain(pos, analyses[ply]) else null
            val refutation = if (needsHelp) MoveExplainer.explain(next, analyses[ply + 1]) else null
            val san = game.sanMoves[ply]
            val comment = when {
                !needsHelp -> "${cls.label} move."
                else -> buildString {
                    append("$san is ${aOrAn(cls.label.lowercase())} ${cls.label.lowercase()}")
                    append(" (${moverBefore.display()} → ${moverAfter.display()} for ${mover.label}).")
                    refutation?.let { r ->
                        append(" It allows ${r.san}: ${r.reasons.firstOrNull()?.replaceFirstChar { it.lowercase() } ?: ""}")
                    }
                    bestExpl?.let { b -> append(" Better was ${b.san} — ${b.reasons.firstOrNull() ?: b.headline}") }
                }
            }
            reviewed += ReviewedMove(ply, mover, san, cls, before, after, wpLoss, cpLoss, bestExpl, refutation, comment)
        }
        return GameReview(reviewed, accuracy(reviewed, Side.WHITE), accuracy(reviewed, Side.BLACK))
    }

    private fun whiteScore(r: SearchResult, stm: Side, terminalFor: com.mrchess.core.rules.Position): Score {
        val s = r.best?.score
        if (s == null) {
            // Terminal position: mate (side to move lost) or draw.
            if (!terminalFor.isCheckmate) return Score(cp = 0)
            return if (terminalFor.sideToMove == Side.WHITE) Score(mate = -1) else Score(mate = 1)
        }
        return if (stm == Side.WHITE) s else s.negate()
    }

    companion object {
        fun winPct(s: Score): Double = 50.0 + 50.0 * s.winningChances

        /** Lichess per-move accuracy: 103.1668 * exp(-0.04354 * winPercentLoss) - 3.1669, averaged. */
        fun accuracy(moves: List<ReviewedMove>, side: Side): Double {
            val mine = moves.filter { it.side == side }
            if (mine.isEmpty()) return 100.0
            return mine.map { (103.1668 * exp(-0.04354 * it.winPercentLoss) - 3.1669).coerceIn(0.0, 100.0) }.average()
        }

        private fun aOrAn(w: String) = if (w.first() in "aeiou") "an" else "a"
    }
}
