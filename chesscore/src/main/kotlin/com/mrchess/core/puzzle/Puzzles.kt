package com.mrchess.core.puzzle

import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlin.math.pow
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

    /** The unplayed puzzle whose rating is closest to [userRating] (repeats once everything was tried). */
    fun next(userRating: Double, played: Set<String>, exclude: String? = null): Puzzle? {
        val pool = all.filter { it.id !in played && it.id != exclude }.ifEmpty { all.filter { it.id != exclude } }
        return pool.minByOrNull { kotlin.math.abs(it.rating - userRating) }
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

    fun k(played: Int) = when {
        played < PROVISIONAL -> 64.0
        played < 30 -> 40.0
        else -> 24.0
    }

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
