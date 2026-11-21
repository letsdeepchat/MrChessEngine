package com.mrchess.core.puzzle

import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlin.math.pow
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * A puzzle in Lichess format: [fen] is the position *before* the opponent's last move,
 * [moves][0] is that move, and the solver plays moves[1], moves[3], … with the opponent's
 * replies in between.
 */
data class Puzzle(val id: String, val fen: String, val moves: List<String>, val rating: Int, val themes: List<String>) {
    /** The position the solver sees after the opponent's setup move. */
    val solverSide: Side get() = Position.fromFen(fen).sideToMove.opposite
    val solverMoves: Int get() = moves.size / 2

    companion object {
        fun parseCsvLine(line: String): Puzzle? {
            val p = line.split(',')
            if (p.size < 5) return null
            return Puzzle(p[0], p[1], p[2].split(' ').filter { it.isNotBlank() }, p[3].toIntOrNull() ?: return null,
                p[4].split(' ').filter { it.isNotBlank() })
        }
    }
}

/** The bundled grandmaster-level set (Lichess puzzle database, CC0; ratings 2500+). */
object PuzzleSet {
    val all: List<Puzzle> by lazy {
        val s = PuzzleSet::class.java.getResourceAsStream("/com/mrchess/core/puzzle/puzzles_gm.csv") ?: return@lazy emptyList()
        s.bufferedReader().useLines { lines -> lines.drop(1).mapNotNull(Puzzle::parseCsvLine).toList() }
    }

    /** Adaptive, randomised pick (see [PuzzleSelector]). */
    fun next(history: PuzzleHistory, exclude: String? = null, random: Random = Random.Default): Puzzle? =
        PuzzleSelector.pick(all, history, exclude, random)
}

/**
 * Everything the selector needs to know about the player's puzzle past.
 *
 * @property solved ids solved at some point; they never come back.
 * @property failed id → value of [attempts] when it was failed; it returns after [PuzzleSelector.RETRY_GAP] more attempts.
 * @property attempts rated puzzle attempts so far (solves and fails).
 */
data class PuzzleHistory(
    val elo: Int = PuzzleRating.START.toInt(),
    val attempts: Int = 0,
    val solvedCount: Int = 0,
    val solved: Set<String> = emptySet(),
    val failed: Map<String, Int> = emptyMap(),
) {
    /** Applies one rated result and returns the new history (Elo, solved set, spaced-repetition queue). */
    fun record(p: Puzzle, solvedCleanly: Boolean): PuzzleHistory {
        val newElo = PuzzleRating.update(elo.toDouble(), p.rating, solvedCleanly, attempts).roundToInt()
        return copy(
            elo = newElo,
            attempts = attempts + 1,
            solvedCount = solvedCount + if (solvedCleanly) 1 else 0,
            solved = if (solvedCleanly) solved + p.id else solved,
            failed = if (solvedCleanly) failed - p.id else failed + (p.id to attempts + 1),
        )
    }
}

/**
 * Chess.com / Lichess-style puzzle choice: never in file order, never a solved puzzle, close to
 * the player's rating, and missed puzzles come back only after a few others (spaced repetition).
 */
object PuzzleSelector {
    const val WINDOW = 150
    const val WIDE_WINDOW = 300
    const val MIN_CANDIDATES = 10
    /** A missed puzzle may return once this many other puzzles have been attempted. */
    const val RETRY_GAP = 5
    /** If nothing is within ±[WIDE_WINDOW], pick among this many closest-rated puzzles. */
    const val NEAREST = 20

    fun eligible(all: List<Puzzle>, h: PuzzleHistory, exclude: String?): List<Puzzle> = all.filter { p ->
        p.id != exclude && p.id !in h.solved && h.failed[p.id].let { at -> at == null || h.attempts - at >= RETRY_GAP }
    }

    fun pick(all: List<Puzzle>, h: PuzzleHistory, exclude: String? = null, random: Random = Random.Default): Puzzle? {
        var pool = eligible(all, h, exclude)
        if (pool.isEmpty()) {
            // Everything solved (or waiting to come back): fall back to missed ones, then to anything.
            pool = all.filter { it.id != exclude && it.id !in h.solved }.ifEmpty { all.filter { it.id != exclude } }
        }
        if (pool.isEmpty()) return null
        fun within(w: Int) = pool.filter { kotlin.math.abs(it.rating - h.elo) <= w }
        val near = within(WINDOW)
        val candidates = when {
            near.size >= MIN_CANDIDATES -> near
            else -> within(WIDE_WINDOW).ifEmpty { pool.sortedBy { kotlin.math.abs(it.rating - h.elo) }.take(NEAREST) }
        }
        return candidates.random(random)
    }
}

/** Walks through one puzzle. Immutable: every action returns a new session. */
data class PuzzleSession(
    val puzzle: Puzzle,
    /** Number of moves from [Puzzle.moves] already on the board. */
    val ply: Int = 0,
    val state: State = State.SETUP,
    /** True once the user missed a move, asked for a hint or the solution (no rating gain). */
    val failed: Boolean = false,
) {
    enum class State { SETUP, YOUR_TURN, OPPONENT_TURN, SOLVED, FAILED }
    enum class Outcome { CORRECT, SOLVED, WRONG, IGNORED }

    /** Parsed once per session instance (the UI reads it on every frame; legal moves stay cached). */
    val position: Position by lazy(LazyThreadSafetyMode.PUBLICATION) { replay(ply) }
    val lastMove: Move? by lazy(LazyThreadSafetyMode.PUBLICATION) { if (ply == 0) null else Move.fromUci(puzzle.moves[ply - 1]) }
    val expected: Move? get() = puzzle.moves.getOrNull(ply)?.let(Move::fromUci)
    val finished: Boolean get() = state == State.SOLVED || state == State.FAILED

    fun positionAt(n: Int): Position = if (n == ply) position else replay(n)

    private fun replay(n: Int): Position {
        var p = Position.fromFen(puzzle.fen)
        for (i in 0 until n) p = p.play(Move.fromUci(puzzle.moves[i]), validate = false)
        return p
    }

    /** Plays the opponent's setup move or reply. */
    fun opponentMove(): PuzzleSession {
        if (state != State.SETUP && state != State.OPPONENT_TURN) return this
        return copy(ply = ply + 1, state = State.YOUR_TURN)
    }

    /**
     * The solver's move. The stored move is correct; any other move that mates immediately is
     * also accepted (as on Lichess).
     */
    fun userMove(m: Move): Pair<PuzzleSession, Outcome> {
        if (state != State.YOUR_TURN) return this to Outcome.IGNORED
        val want = expected ?: return this to Outcome.IGNORED
        val pos = position
        if (!pos.isLegal(m)) return this to Outcome.IGNORED
        val mates = pos.play(m).isCheckmate
        if (m.uci != want.uci && !mates) return copy(state = State.FAILED, failed = true) to Outcome.WRONG
        val next = ply + 1
        if (mates || next >= puzzle.moves.size) {
            // Show the user's own move if it differs from the stored one.
            val s = if (m.uci == want.uci) copy(ply = next, state = State.SOLVED)
                    else copy(puzzle = puzzle.copy(moves = puzzle.moves.take(ply) + m.uci), ply = next, state = State.SOLVED)
            return s to Outcome.SOLVED
        }
        return copy(ply = next, state = State.OPPONENT_TURN) to Outcome.CORRECT
    }

    fun markAssisted() = copy(failed = true)

    /** Restart from the setup move without changing the rating again. */
    fun retry() = PuzzleSession(puzzle, failed = true)

    /** Shows the full solution. */
    fun reveal() = copy(ply = puzzle.moves.size, state = State.FAILED, failed = true)
}

/**
 * Elo estimate from puzzle results. Everyone starts at [START]; K shrinks as more puzzles are
 * played (provisional for the first [PROVISIONAL] puzzles).
 */
object PuzzleRating {
    const val START = 1500.0
    const val PROVISIONAL = 10

    fun expected(user: Double, puzzle: Int): Double = 1.0 / (1.0 + 10.0.pow((puzzle - user) / 400.0))

    /** K = 32 during the provisional period, 16 afterwards. */
    fun k(played: Int) = if (played < PROVISIONAL) 32.0 else 16.0

    fun update(user: Double, puzzle: Int, solved: Boolean, played: Int): Double =
        (user + k(played) * ((if (solved) 1.0 else 0.0) - expected(user, puzzle))).coerceIn(400.0, 3400.0)

    fun title(r: Double): String = when (r.roundToInt()) {
        in 2500..9999 -> "Grandmaster level"
        in 2400..2499 -> "International Master level"
        in 2300..2399 -> "FIDE Master level"
        in 2200..2299 -> "Candidate Master level"
        in 2000..2199 -> "Expert"
        in 1800..1999 -> "Class A"
        in 1600..1799 -> "Class B"
        in 1400..1599 -> "Class C"
        else -> "Club player"
    }
}
