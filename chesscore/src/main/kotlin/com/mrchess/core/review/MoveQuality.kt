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
    BOOK("Book", "📖", 0xFFA88865),
    INACCURACY("Inaccuracy", "?!", 0xFFF7C631),
    MISTAKE("Mistake", "?", 0xFFE58F2A),
    BLUNDER("Blunder", "??", 0xFFCA3431),
    FORCED("Forced", "□", 0xFF9E9E9E);

    val isError: Boolean get() = this == INACCURACY || this == MISTAKE || this == BLUNDER

    companion object {
        /** Order used by the summary table. */
        val SUMMARY = listOf(BRILLIANT, GREAT, BEST, GOOD, BOOK, INACCURACY, MISTAKE, BLUNDER)
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
    /** Opening name when the move is (or stays) in the built-in opening book. */
    val opening: String? = null,
)

/**
 * Classifies a played move with the win-probability model (Lichess / Chess.com style).
 * Evaluations are from the mover's point of view.
 *
 *   Win% = 50 + 50 · (2 / (1 + e^(−0.00368208 · cp)) − 1)
 *   winProbLoss = max(0, Win%(best) − Win%(played)),  cpLoss = max(0, cp(best) − cp(played))
 *
 *  Book        in the opening book (or within the first 6 plies with cpLoss < 35) and winProbLoss ≤ 7
 *  Brilliant   sacrifice of ≥ 2 pawns of material, winProbLoss ≤ 2.5 and Win% after ≥ 45
 *  Great       best move when the 2nd-best is ≥ 12 Win% worse (the only move that holds)
 *  Best        the engine's top move, or winProbLoss ≤ 1.5
 *  Good        1.5 < winProbLoss ≤ 7
 *  Inaccuracy  7 < winProbLoss ≤ 14 and cpLoss ≥ 50
 *  Mistake     14 < winProbLoss ≤ 24 and cpLoss ≥ 100
 *  Blunder     winProbLoss > 24 and cpLoss ≥ 200 and still not winning by more than +6, or a move that
 *              allows a forced mate the best move avoided
 * A move that misses a band's centipawn floor drops to the next milder band.
 */
object MoveQualityClassifier {

    data class Inputs(
        val eBest: Score,
        val eSecond: Score?,
        val ePlayed: Score,
        val playedIsBest: Boolean,
        /** Material given up over the next two plies is at least 200 cp. */
        val materialSacrifice: Boolean,
        val onlyLegalMove: Boolean,
        /** Check evasions, recaptures and mate-in-one are never Brilliant or Great. */
        val routineMove: Boolean,
        val book: Boolean = false,
    )

    fun classify(i: Inputs): MoveQuality {
        if (i.onlyLegalMove) return MoveQuality.FORCED
        val wpBest = winPct(i.eBest)
        val wpAfter = if (i.playedIsBest) wpBest else winPct(i.ePlayed)
        val loss = (wpBest - wpAfter).coerceAtLeast(0.0)
        val cpLoss = if (i.playedIsBest) 0 else MoveExplainer.cpDelta(i.eBest, i.ePlayed)
        val cpAfter = i.ePlayed.comparable.coerceIn(-2000, 2000)
        val allowsMate = (i.ePlayed.mate ?: 0) < 0 && (i.eBest.mate ?: 1) > 0
        val gap = i.eSecond?.let { wpBest - winPct(it) } ?: 0.0
        return when {
            i.book && loss <= 7.0 -> MoveQuality.BOOK
            allowsMate -> MoveQuality.BLUNDER
            loss > 24.0 && cpLoss >= 200 && cpAfter <= 600 -> MoveQuality.BLUNDER
            loss > 14.0 && cpLoss >= 100 -> MoveQuality.MISTAKE
            loss > 7.0 && cpLoss >= 50 -> MoveQuality.INACCURACY
            i.materialSacrifice && !i.routineMove && loss <= 2.5 && wpAfter >= 45.0 -> MoveQuality.BRILLIANT
            i.playedIsBest || loss <= 1.5 ->
                if (gap >= 12.0 && !i.routineMove) MoveQuality.GREAT else MoveQuality.BEST
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
        val sacrifice = materialDropOverTwoPlies(before, move, resAfter.bestMove, us) >= 200
        // Recaptures, check evasions and mate-in-one are never "brilliant"/"great" however forced they are.
        val routine = before.inCheck() || after.isCheckmate ||
            (previousMove != null && previousMove.to == move.to && before.isCapture(move))
        val delta = if (playedIsBest) 0 else MoveExplainer.cpDelta(eBest, ePlayed)
        // Opening book: the game must still be in theory before this move.
        val ply = (before.fullmoveNumber - 1) * 2 + if (us == Side.BLACK) 1 else 0
        val opening = OpeningBook.lookup(after)
        val book = ply < OpeningBook.MAX_PLY && OpeningBook.inBook(before) &&
            (opening != null || (ply < 6 && delta < 35))
        val quality = classify(
            Inputs(eBest, eSecond, ePlayed, playedIsBest, sacrifice, before.legalMoves().size == 1, routine, book),
        )
        val toWhite = { s: Score -> if (us == Side.WHITE) s else s.negate() }
        val bestExpl = if (!playedIsBest && quality != MoveQuality.FORCED) MoveExplainer.explain(before, resBefore) else null
        val refutation = if (quality.isError && resAfter.best != null) MoveExplainer.explain(after, resAfter) else null
        val san = before.toSan(move)
        val rationale = buildString {
            val word = quality.label.lowercase()
            append(
                when {
                    quality == MoveQuality.BEST -> "$san is the best move"
                    quality == MoveQuality.BOOK -> "$san is a book move" + (opening?.let { " (${it.eco} ${it.name})" } ?: "")
                    quality == MoveQuality.FORCED -> "$san was the only legal move"
                    quality.isError -> "$san is ${article(word)} $word"
                    else -> "$san is ${article(word)} $word move"
                },
            )
            if (delta > 0 && quality != MoveQuality.BOOK) append(" (${MoveExplainer.deltaWords(delta)} worse than the best)")
            append('.')
            if (quality == MoveQuality.BRILLIANT && sacrifice) append(" A sound sacrifice: material is given up for a lasting advantage.")
            if (quality == MoveQuality.GREAT)
                append(" It was the only good move; the alternatives lose ground.")
            refutation?.let { r ->
                append(" It allows ${r.san}: ${r.reasons.firstOrNull()?.replaceFirstChar { it.lowercase() } ?: ""}")
            }
            bestExpl?.takeIf { quality != MoveQuality.BOOK }?.let { b ->
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
            opening = opening?.name,
        )
    }

    /** Material the mover is down after their move and the opponent's best reply (cp; positive = given up). */
    fun materialDropOverTwoPlies(before: Position, move: Move, replyUci: String?, us: Side): Int {
        var p = before.play(move)
        replyUci?.let { u -> runCatching { Move.fromUci(u) }.getOrNull()?.takeIf { p.isLegal(it) }?.let { p = p.play(it) } }
        val diff = p.materialBalance() - before.materialBalance()
        return -(if (us == Side.WHITE) diff else -diff)
    }

    /** Expected win percentage for the side the score belongs to (mates count as 0 / 100). */
    fun winPct(s: Score): Double = 50.0 + 50.0 * s.winningChances

    /** Per-move accuracy from win% loss (Book moves count as 100). */
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
                acc.getValue(side) += if (a.quality == MoveQuality.BOOK) 100.0 else MoveQualityClassifier.moveAccuracy(a.winPercentLoss)
            }
            return GameSummary(
                acc.getValue(Side.WHITE).takeIf { it.isNotEmpty() }?.average(),
                acc.getValue(Side.BLACK).takeIf { it.isNotEmpty() }?.average(),
                counts, first, moves.count { it.second != null }, moves.size,
            )
        }
    }
}
