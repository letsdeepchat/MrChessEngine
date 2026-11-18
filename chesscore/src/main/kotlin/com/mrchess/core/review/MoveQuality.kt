package com.mrchess.core.review

import com.mrchess.core.engine.Score
import com.mrchess.core.engine.SearchResult
import com.mrchess.core.explain.Explanation
import com.mrchess.core.explain.MoveExplainer
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlin.math.exp
import kotlin.math.roundToInt

/** Move quality badges in Chess.com order, best to worst. Colours are ARGB. */
enum class MoveQuality(val label: String, val symbol: String, val color: Long) {
    BRILLIANT("Brilliant", "!!", 0xFF1BACA6),
    GREAT("Great", "!", 0xFF5C8BB0),
    BEST("Best", "★", 0xFF96BC4B),
    EXCELLENT("Excellent", "👍", 0xFF96BC4B),
    GOOD("Good", "✓", 0xFF96AF8B),
    BOOK("Book", "📖", 0xFFA88865),
    INACCURACY("Inaccuracy", "?!", 0xFFF7C631),
    MISTAKE("Mistake", "?", 0xFFE58F2A),
    MISS("Miss", "❌", 0xFFEE534F),
    BLUNDER("Blunder", "??", 0xFFCA3431),
    FORCED("Forced", "□", 0xFF9E9E9E);

    val isError: Boolean get() = this == INACCURACY || this == MISTAKE || this == MISS || this == BLUNDER

    companion object {
        /** Order used by the summary table. */
        val SUMMARY = listOf(BRILLIANT, GREAT, BEST, EXCELLENT, GOOD, BOOK, INACCURACY, MISTAKE, MISS, BLUNDER)
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
    /** Full-move number of the move (1, 2, …), for the phase breakdown. */
    val moveNumber: Int = 0,
    /** Queens, rooks, bishops and knights on the board before the move. */
    val piecesBefore: Int = 14,
    /** Win% gap between the best and second-best move: high means a sharp, tactical position. */
    val sharpness: Double = 0.0,
) {
    /** Accuracy of this move, 0..100, with caps so errors weigh on the game score. */
    val accuracy: Double get() = MoveQualityClassifier.moveAccuracy(quality, winPercentLoss)
}

/**
 * Classifies a played move with the win-probability model (Chess.com style). Evaluations are
 * from the mover's point of view.
 *
 *   Win%(cp) = 50 + 50 · (2 / (1 + e^(−0.00368208 · clamp(cp, ±1500))) − 1)
 *   winProbLoss = max(0, Win%(best) − Win%(played)),  cpLoss = max(0, cp(best) − cp(played))
 *
 *  Book        in the opening book (or the first 6 plies with cpLoss ≤ 25), unless it loses > 8 %
 *  Brilliant   sacrifice of ≥ 200 cp, winProbLoss ≤ 2 and Win% after ≥ 48
 *  Great       the only move: best minus 2nd best ≥ 15 Win%, winProbLoss ≤ 2
 *  Best        the engine's top move, or winProbLoss ≤ 1
 *  Excellent   winProbLoss ≤ 4 (and cpLoss ≤ 35)
 *  Good        winProbLoss ≤ 8 (and cpLoss ≤ 70)
 *  Inaccuracy  winProbLoss ≤ 15 (or 70 < cpLoss ≤ 130)
 *  Miss        winning (Win% ≥ 68) or the opponent just blundered (≥ 18 %), the move throws ≥ 15 %
 *              of it away, but the position stays roughly level or better (Win% after ≥ 45); also a missed forced mate
 *              that still leaves you winning
 *  Mistake     winProbLoss ≤ 25 (or 130 < cpLoss ≤ 240)
 *  Blunder     winProbLoss > 25, or cpLoss > 240, or a move into a forced mate
 * The centipawn clauses are ignored once the game is already decided (still above +6 after the
 * move, or already below −6 before it), so routine moves in won or lost positions are not
 * flagged.
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
        /** The opponent's previous move lost this much win% (null if unknown). */
        val opponentLoss: Double? = null,
    )

    fun classify(i: Inputs): MoveQuality {
        if (i.onlyLegalMove) return MoveQuality.FORCED
        val wpBest = winPct(i.eBest)
        val wpAfter = if (i.playedIsBest) wpBest else winPct(i.ePlayed)
        val loss = (wpBest - wpAfter).coerceAtLeast(0.0)
        val cpLoss = if (i.playedIsBest) 0 else MoveExplainer.cpDelta(i.eBest, i.ePlayed)
        val cpAfter = i.ePlayed.comparable.coerceIn(-2000, 2000)
        val cpBefore = i.eBest.comparable.coerceIn(-2000, 2000)
        val decided = cpAfter > 600 || cpBefore < -600
        val cp = if (decided) 0 else cpLoss
        val intoMate = (i.ePlayed.mate ?: 0) < 0 && (i.eBest.mate ?: 1) > 0
        val missedMate = !i.playedIsBest && (i.eBest.mate ?: 0) > 0 && (i.ePlayed.mate ?: 0) <= 0
        val hadChance = wpBest >= 68.0 || (i.opponentLoss ?: 0.0) >= 18.0
        val gap = i.eSecond?.let { wpBest - winPct(it) } ?: 0.0
        return when {
            i.book && loss <= 8.0 -> MoveQuality.BOOK
            intoMate -> MoveQuality.BLUNDER
            missedMate -> if (wpAfter >= 68.0) MoveQuality.MISS else MoveQuality.BLUNDER
            hadChance && loss >= 15.0 && wpAfter >= 45.0 -> MoveQuality.MISS
            loss > 25.0 || cp > 240 -> MoveQuality.BLUNDER
            loss > 15.0 || cp > 130 -> MoveQuality.MISTAKE
            loss > 8.0 || cp > 70 -> MoveQuality.INACCURACY
            i.materialSacrifice && !i.routineMove && loss <= 2.0 && wpAfter >= 48.0 -> MoveQuality.BRILLIANT
            gap >= 15.0 && loss <= 2.0 && !i.routineMove && (i.playedIsBest || loss <= 1.0) -> MoveQuality.GREAT
            i.playedIsBest || loss <= 1.0 -> MoveQuality.BEST
            loss <= 4.0 && cp <= 35 -> MoveQuality.EXCELLENT
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
        /** Win% the opponent's previous move gave away, for "Miss". */
        opponentLoss: Double? = null,
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
            (opening != null || (ply < 6 && delta <= 25))
        val quality = classify(
            Inputs(eBest, eSecond, ePlayed, playedIsBest, sacrifice, before.legalMoves().size == 1, routine, book, opponentLoss),
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
            if (quality == MoveQuality.MISS)
                append(if ((eBest.mate ?: 0) > 0) " There was a forced mate." else " You had a chance to win more here.")
            refutation?.let { r ->
                append(" It allows ${r.san}: ${r.reasons.firstOrNull()?.replaceFirstChar { it.lowercase() } ?: ""}")
            }
            bestExpl?.takeIf { quality != MoveQuality.BOOK }?.let { b ->
                if (quality.isError || quality == MoveQuality.GOOD || quality == MoveQuality.EXCELLENT) append(" Better was ${b.san}: ${b.reasons.firstOrNull() ?: b.headline}")
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
            moveNumber = before.fullmoveNumber,
            piecesBefore = (0 until 64).count { sq -> before[sq]?.type.let { it != null && it != PieceType.PAWN && it != PieceType.KING } },
            sharpness = eSecond?.let { (winPct(eBest) - winPct(it)).coerceAtLeast(0.0) } ?: 0.0,
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
    fun winPct(s: Score): Double {
        val m = s.mate
        if (m != null) return if (m > 0) 100.0 else 0.0
        val cp = (s.cp ?: 0).coerceIn(-1500, 1500)
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * cp)) - 1.0)
    }

    /** Raw per-move accuracy from win% loss (Chess.com / Lichess curve). */
    fun moveAccuracy(winPercentLoss: Double): Double =
        (103.1668 * exp(-0.04354 * winPercentLoss) - 3.1669).coerceIn(0.0, 100.0)

    /** Per-move accuracy with category caps: Book 100, Inaccuracy ≤ 65, Mistake/Miss ≤ 35, Blunder ≤ 10 (0 past 35 %). */
    fun moveAccuracy(q: MoveQuality, winPercentLoss: Double): Double {
        val raw = moveAccuracy(winPercentLoss)
        return when (q) {
            MoveQuality.BOOK, MoveQuality.FORCED -> 100.0
            MoveQuality.INACCURACY -> minOf(raw, 65.0)
            MoveQuality.MISTAKE, MoveQuality.MISS -> minOf(raw, 35.0)
            MoveQuality.BLUNDER -> if (winPercentLoss > 35.0) 0.0 else minOf(raw, 10.0)
            else -> raw
        }
    }

    private fun article(w: String) = if (w.first().lowercaseChar() in "aeiou") "an" else "a"
}

/** How well one side played in part of the game. */
data class PhaseStat(val accuracy: Double, val moves: Int) {
    /** Chess.com-style icon for a phase or skill. */
    val badge: MoveQuality get() = badgeFor(accuracy)

    companion object {
        fun badgeFor(acc: Double): MoveQuality = when {
            acc >= 95 -> MoveQuality.BEST
            acc >= 85 -> MoveQuality.EXCELLENT
            acc >= 75 -> MoveQuality.GOOD
            acc >= 60 -> MoveQuality.INACCURACY
            acc >= 45 -> MoveQuality.MISTAKE
            else -> MoveQuality.BLUNDER
        }
    }
}

enum class GamePhase(val label: String) { OPENING("Opening"), MIDDLEGAME("Middlegame"), ENDGAME("Endgame") }

/** Per-side accuracy, badge counts, phase breakdown and skill indicators for a (partially) annotated game. */
data class GameSummary(
    val accuracyWhite: Double?,
    val accuracyBlack: Double?,
    val counts: Map<Side, Map<MoveQuality, Int>>,
    /** First move index (0-based) with a given quality, per side. */
    val firstIndex: Map<Side, Map<MoveQuality, Int>>,
    val annotated: Int,
    val total: Int,
    val phases: Map<Side, Map<GamePhase, PhaseStat>> = emptyMap(),
    /** Accuracy in sharp positions (best move ≥ 10 % better than the 2nd best). */
    val tactics: Map<Side, PhaseStat> = emptyMap(),
    /** Share of Best/Excellent/Good/Great/Brilliant moves in quiet positions, as a percentage. */
    val strategy: Map<Side, PhaseStat> = emptyMap(),
) {
    fun count(side: Side, q: MoveQuality): Int = counts[side]?.get(q) ?: 0
    fun accuracy(side: Side): Double? = if (side == Side.WHITE) accuracyWhite else accuracyBlack
    fun phase(side: Side, p: GamePhase): PhaseStat? = phases[side]?.get(p)

    /** Tactics icon: accuracy in sharp positions, pulled down by misses and blunders. */
    fun tacticsBadge(side: Side): MoveQuality? {
        val t = tactics[side] ?: return null
        val slips = count(side, MoveQuality.MISS) + count(side, MoveQuality.BLUNDER)
        return when {
            slips == 0 && t.accuracy >= 85 -> MoveQuality.EXCELLENT
            slips <= 1 && t.accuracy >= 70 -> MoveQuality.GOOD
            else -> MoveQuality.INACCURACY
        }
    }

    fun strategyBadge(side: Side): MoveQuality? {
        val s = strategy[side] ?: return null
        return when {
            s.accuracy >= 80 -> MoveQuality.EXCELLENT
            s.accuracy >= 60 -> MoveQuality.GOOD
            else -> MoveQuality.INACCURACY
        }
    }

    /**
     * Single-game performance rating: (base + (accuracy − 75) · 32), rounded to 50, 400..3200.
     * [base] is the opponent's rating when known (e.g. the engine's Elo), else this side's own.
     */
    fun gameRating(side: Side, base: Int): Int? {
        val acc = accuracy(side) ?: return null
        val raw = base + (acc - 75.0) * 32.0
        return ((raw / 50.0).roundToInt() * 50).coerceIn(400, 3200)
    }

    companion object {
        /**
         * Game accuracy: the average of the weighted mean and the weighted harmonic mean of the
         * per-move accuracies (book moves weigh half). The harmonic mean is what lets a few bad
         * moves pull the score down the way they do on Chess.com.
         */
        fun gameAccuracy(moves: List<Pair<Double, Double>>): Double? {
            if (moves.isEmpty()) return null
            val w = moves.sumOf { it.second }
            val mean = moves.sumOf { it.first * it.second } / w
            val harmonic = w / moves.sumOf { it.second / it.first.coerceAtLeast(5.0) }
            return ((mean + harmonic) / 2.0).coerceIn(0.0, 100.0)
        }

        fun phaseOf(a: MoveAnnotation): GamePhase = when {
            a.moveNumber >= 26 || a.piecesBefore <= 6 -> GamePhase.ENDGAME
            a.moveNumber <= 10 -> GamePhase.OPENING
            else -> GamePhase.MIDDLEGAME
        }

        /** [moves] = (mover, annotation or null) in game order. */
        fun of(moves: List<Pair<Side, MoveAnnotation?>>): GameSummary {
            val counts = Side.entries.associateWith { mutableMapOf<MoveQuality, Int>() }
            val first = Side.entries.associateWith { mutableMapOf<MoveQuality, Int>() }
            val acc = Side.entries.associateWith { mutableListOf<Pair<Double, Double>>() }
            val byPhase = Side.entries.associateWith { side -> GamePhase.entries.associateWith { mutableListOf<Pair<Double, Double>>() } }
            val sharp = Side.entries.associateWith { mutableListOf<Pair<Double, Double>>() }
            val quiet = Side.entries.associateWith { mutableListOf<Boolean>() }
            moves.forEachIndexed { i, (side, a) ->
                if (a == null) return@forEachIndexed
                counts.getValue(side).merge(a.quality, 1, Int::plus)
                first.getValue(side).putIfAbsent(a.quality, i)
                if (a.quality == MoveQuality.FORCED) return@forEachIndexed
                val weight = if (a.quality == MoveQuality.BOOK) 0.5 else 1.0
                val entry = a.accuracy to weight
                acc.getValue(side) += entry
                byPhase.getValue(side).getValue(phaseOf(a)) += entry
                if (a.quality == MoveQuality.BOOK) return@forEachIndexed
                if (a.sharpness >= 10.0) sharp.getValue(side) += entry
                else quiet.getValue(side) += a.quality in setOf(MoveQuality.BRILLIANT, MoveQuality.GREAT, MoveQuality.BEST, MoveQuality.EXCELLENT, MoveQuality.GOOD)
            }
            fun stat(l: List<Pair<Double, Double>>) = gameAccuracy(l)?.let { PhaseStat(it, l.size) }
            return GameSummary(
                gameAccuracy(acc.getValue(Side.WHITE)),
                gameAccuracy(acc.getValue(Side.BLACK)),
                counts, first, moves.count { it.second != null }, moves.size,
                phases = Side.entries.associateWith { side ->
                    GamePhase.entries.mapNotNull { p -> stat(byPhase.getValue(side).getValue(p))?.let { p to it } }.toMap()
                },
                tactics = Side.entries.mapNotNull { side -> stat(sharp.getValue(side))?.let { side to it } }.toMap(),
                strategy = Side.entries.mapNotNull { side ->
                    quiet.getValue(side).takeIf { it.isNotEmpty() }?.let { q -> side to PhaseStat(100.0 * q.count { it } / q.size, q.size) }
                }.toMap(),
            )
        }
    }
}
