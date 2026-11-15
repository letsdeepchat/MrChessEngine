package com.mrchess.core.review

import com.mrchess.core.engine.Score
import com.mrchess.core.engine.SearchResult
import com.mrchess.core.explain.Explanation
import com.mrchess.core.explain.MoveExplainer
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlin.math.exp

/** Move quality badges, best to worst. Colours are ARGB. */
enum class MoveQuality(val label: String, val symbol: String, val color: Long) {
    BRILLIANT("Brilliant", "!!", 0xFF1BACA6),
    GREAT("Great", "!", 0xFF5C8BB0),
    BEST("Best", "★", 0xFF81B64C),
    GOOD("Good", "✓", 0xFF96BC4B),
    INACCURACY("Inaccuracy", "?!", 0xFFF7C631),
    MISTAKE("Mistake", "?", 0xFFE58F2A),
    BLUNDER("Blunder", "??", 0xFFCA3431),
    FORCED("Forced", "□", 0xFF9E9E9E);

    val isError: Boolean get() = this == INACCURACY || this == MISTAKE || this == BLUNDER

    companion object {
        /** Order used by the summary table. */
        val SUMMARY = listOf(BRILLIANT, GREAT, BEST, GOOD, INACCURACY, MISTAKE, BLUNDER)
    }
}

/** Everything the app knows about one played move after engine analysis. */
data class MoveAnnotation(
    val quality: MoveQuality,
    /** E_best − E_played in centipawns, mover's point of view (mates clamped to ±2000). */
    val deltaCp: Int,
    /** Evaluations from White's point of view. */
    val evalBeforeWhite: Score,
    val evalAfterWhite: Score,
    /** Drop in the mover's win probability, 0..100. */
    val winPercentLoss: Double,
    val bestMoveSan: String?,
    val bestMoveUci: String?,
    /** Why the engine's move is best (null when the played move was the best). */
    val bestExplanation: Explanation?,
    /** What the opponent can now do against the played move (only for inaccuracies or worse). */
    val refutation: Explanation?,
    val rationale: String,
)

/**
 * Classifies a played move from Stockfish MultiPV-2 evaluations, in centipawns from the mover's
 * perspective. Δ = E_best − E_played.
 *
 *  Brilliant  sound sacrifice (≥ 1 pawn net drop over the next 2 plies) with Δ ≤ 15 and E_played ≥ +100,
 *             or the sole winning move (E_best − E_second ≥ 200, Δ ≤ 15) that is not a plain recapture
 *             or check evasion
 *  Great      only good move: E_best − E_second ≥ 120 and Δ ≤ 15
 *  Best       Δ ≤ 10 (the engine's top move counts as Δ = 0)
 *  Good       10 < Δ ≤ 45
 *  Inaccuracy 45 < Δ ≤ 100
 *  Mistake    100 < Δ ≤ 250
 *  Blunder    Δ > 250, or the move allows a forced mate that the best move avoided
 */
object MoveQualityClassifier {

    data class Inputs(
        val eBest: Score,
        val eSecond: Score?,
        val ePlayed: Score,
        val playedIsBest: Boolean,
        val materialSacrifice: Boolean,
        val onlyLegalMove: Boolean,
        val routineMove: Boolean,
    )

    fun classify(i: Inputs): MoveQuality {
        if (i.onlyLegalMove) return MoveQuality.FORCED
        val delta = if (i.playedIsBest) 0 else MoveExplainer.cpDelta(i.eBest, i.ePlayed)
        val gap = i.eSecond?.let { MoveExplainer.cpDelta(i.eBest, it) } ?: 0
        val allowsMate = (i.ePlayed.mate ?: 0) < 0 && (i.eBest.mate ?: 1) > 0
        val played = i.ePlayed.comparable.coerceIn(-2000, 2000)
        val secondValue = i.eSecond?.comparable?.coerceIn(-2000, 2000) ?: 0
        return when {
            allowsMate || delta > 250 -> MoveQuality.BLUNDER
            delta > 100 -> MoveQuality.MISTAKE
            delta > 45 -> MoveQuality.INACCURACY
            delta <= 15 && i.materialSacrifice && played >= 100 -> MoveQuality.BRILLIANT
            // "Sole winning move": the alternatives must not win too, and it must not be routine.
            delta <= 15 && gap >= 200 && !i.routineMove && secondValue < 150 -> MoveQuality.BRILLIANT
            delta <= 15 && gap >= 120 && !i.routineMove -> MoveQuality.GREAT
            delta <= 10 -> MoveQuality.BEST
            else -> MoveQuality.GOOD
        }
    }

    /**
     * Annotates [move] played in [before]. [resBefore] should be a MultiPV-2 search of [before];
     * [resAfter] a search of the position after the move (empty if it is terminal).
     * [previousMove] lets us recognise recaptures, which are never "brilliant".
     */
    fun annotate(
        before: Position,
        move: Move,
        resBefore: SearchResult,
        resAfter: SearchResult,
        previousMove: Move? = null,
    ): MoveAnnotation {
        val us = before.sideToMove
        val after = before.play(move)
        val eBest = resBefore.best?.score ?: Score(cp = 0)
        val ePlayed = when {
            after.isCheckmate -> Score(mate = 1)
            after.legalMoves().isEmpty() -> Score(cp = 0)
            else -> resAfter.best?.score?.negate() ?: Score(cp = 0)
        }
        val playedIsBest = resBefore.bestMove == move.uci
        val eSecond = resBefore.second?.score
        val sacrifice = materialDropOverTwoPlies(before, move, resAfter.bestMove, us) >= 100
        // Recaptures, check evasions and mate-in-one are never "brilliant"/"great" however forced they are.
        val routine = before.inCheck() || after.isCheckmate ||
            (previousMove != null && previousMove.to == move.to && before.isCapture(move))
        val quality = classify(
            Inputs(eBest, eSecond, ePlayed, playedIsBest, sacrifice, before.legalMoves().size == 1, routine),
        )
        val delta = if (playedIsBest) 0 else MoveExplainer.cpDelta(eBest, ePlayed)
        val toWhite = { s: Score -> if (us == Side.WHITE) s else s.negate() }
        val bestExpl = if (!playedIsBest && quality != MoveQuality.FORCED) MoveExplainer.explain(before, resBefore) else null
        val refutation = if (quality.isError && resAfter.best != null) MoveExplainer.explain(after, resAfter) else null
        val san = before.toSan(move)
        val rationale = buildString {
            val word = quality.label.lowercase()
            append(
                when {
                    quality == MoveQuality.BEST -> "$san is the best move"
                    quality == MoveQuality.FORCED -> "$san was the only legal move"
                    quality.isError -> "$san is ${article(word)} $word"
                    else -> "$san is ${article(word)} $word move"
                },
            )
            if (delta > 0) append(" (${MoveExplainer.deltaWords(delta)} worse than the best)")
            append('.')
            if (quality == MoveQuality.BRILLIANT && sacrifice) append(" A sound sacrifice: material is given up for a lasting advantage.")
            if (quality == MoveQuality.GREAT || (quality == MoveQuality.BRILLIANT && !sacrifice))
                append(" It was the only good move; the alternatives lose ground.")
            refutation?.let { r ->
                append(" It allows ${r.san}: ${r.reasons.firstOrNull()?.replaceFirstChar { it.lowercase() } ?: ""}")
            }
            bestExpl?.let { b ->
                if (quality.isError || quality == MoveQuality.GOOD) append(" Better was ${b.san}: ${b.reasons.firstOrNull() ?: b.headline}")
            }
        }
        val bestUci = resBefore.bestMove
        return MoveAnnotation(
            quality = quality,
            deltaCp = delta,
            evalBeforeWhite = toWhite(eBest),
            evalAfterWhite = toWhite(ePlayed),
            winPercentLoss = if (playedIsBest) 0.0 else (winPct(eBest) - winPct(ePlayed)).coerceAtLeast(0.0),
            bestMoveSan = bestUci?.let { u -> runCatching { before.toSan(Move.fromUci(u)) }.getOrNull() },
            bestMoveUci = bestUci,
            bestExplanation = bestExpl,
            refutation = refutation,
            rationale = rationale,
        )
    }

    /** Material the mover is down after their move and the opponent's best reply (cp; positive = given up). */
    fun materialDropOverTwoPlies(before: Position, move: Move, replyUci: String?, us: Side): Int {
        var p = before.play(move)
        replyUci?.let { u -> runCatching { Move.fromUci(u) }.getOrNull()?.takeIf { p.isLegal(it) }?.let { p = p.play(it) } }
        val diff = p.materialBalance() - before.materialBalance()
        return -(if (us == Side.WHITE) diff else -diff)
    }

    fun winPct(s: Score): Double = 50.0 + 50.0 * s.winningChances

    /** Lichess per-move accuracy from win% loss. */
    fun moveAccuracy(winPercentLoss: Double): Double =
        (103.1668 * exp(-0.04354 * winPercentLoss) - 3.1669).coerceIn(0.0, 100.0)

    private fun article(w: String) = if (w.first().lowercaseChar() in "aeiou") "an" else "a"
}

/** Per-side accuracy and badge counts for a (partially) annotated game. */
data class GameSummary(
    val accuracyWhite: Double?,
    val accuracyBlack: Double?,
    val counts: Map<Side, Map<MoveQuality, Int>>,
    /** First move index (0-based) with a given quality, per side. */
    val firstIndex: Map<Side, Map<MoveQuality, Int>>,
    val annotated: Int,
    val total: Int,
) {
    fun count(side: Side, q: MoveQuality): Int = counts[side]?.get(q) ?: 0

    companion object {
        /** [moves] = (mover, annotation or null) in game order. */
        fun of(moves: List<Pair<Side, MoveAnnotation?>>): GameSummary {
            val counts = Side.entries.associateWith { mutableMapOf<MoveQuality, Int>() }
            val first = Side.entries.associateWith { mutableMapOf<MoveQuality, Int>() }
            val acc = Side.entries.associateWith { mutableListOf<Double>() }
            moves.forEachIndexed { i, (side, a) ->
                if (a == null) return@forEachIndexed
                counts.getValue(side).merge(a.quality, 1, Int::plus)
                first.getValue(side).putIfAbsent(a.quality, i)
                acc.getValue(side) += MoveQualityClassifier.moveAccuracy(a.winPercentLoss)
            }
            return GameSummary(
                acc.getValue(Side.WHITE).takeIf { it.isNotEmpty() }?.average(),
                acc.getValue(Side.BLACK).takeIf { it.isNotEmpty() }?.average(),
                counts, first, moves.count { it.second != null }, moves.size,
            )
        }
    }
}
