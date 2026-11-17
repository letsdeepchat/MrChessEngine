package com.mrchess.app.ui

import com.mrchess.app.audio.BoardSoundManager
import com.mrchess.app.data.PuzzleStats
import com.mrchess.app.data.SettingsRepository
import com.mrchess.core.puzzle.Puzzle
import com.mrchess.core.puzzle.PuzzleRating
import com.mrchess.core.puzzle.PuzzleSession
import com.mrchess.core.puzzle.PuzzleSet
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PuzzleUiState(
    val session: PuzzleSession? = null,
    val stats: PuzzleStats = PuzzleStats(),
    val selected: Int? = null,
    val targets: Set<Int> = emptySet(),
    /** Square of the piece to move, after Hint. */
    val hintSquare: Int? = null,
    /** Whether this puzzle already changed the rating (only the first result counts). */
    val rated: Boolean = false,
    val ratingChange: Double? = null,
    val message: String? = null,
    val good: Boolean? = null,
    val loading: Boolean = true,
)

/**
 * Grandmaster-level puzzles (Lichess puzzle database, CC0, rated 2500+). The next puzzle is the
 * unplayed one closest to the player's estimated Elo; the first attempt at each puzzle updates
 * the estimate (see [PuzzleRating]). Fully offline.
 */
class PuzzleController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val sounds: BoardSoundManager,
) {
    private val _state = MutableStateFlow(PuzzleUiState())
    val state: StateFlow<PuzzleUiState> = _state
    private val s get() = _state.value
    private var job: Job? = null

    init {
        scope.launch {
            val first = settings.puzzleStats.first()
            _state.update { it.copy(stats = first) }
            withContext(Dispatchers.Default) { PuzzleSet.all }
            next()
            settings.puzzleStats.collect { st -> _state.update { it.copy(stats = st) } }
        }
    }

    fun next() {
        val p = PuzzleSet.next(s.stats.rating, s.stats.seen, exclude = s.session?.puzzle?.id) ?: return
        start(p, rated = false)
    }

    private fun start(p: Puzzle, rated: Boolean) {
        job?.cancel()
        val session = PuzzleSession(p, failed = rated)
        _state.update {
            it.copy(session = session, selected = null, targets = emptySet(), hintSquare = null, rated = rated,
                ratingChange = if (rated) it.ratingChange else null, message = null, good = null, loading = false)
        }
        job = scope.launch {
            delay(700)
            opponentReply()
        }
    }

    private fun opponentReply() {
        val cur = s.session ?: return
        val m = cur.expected ?: return
        val before = cur.position
        _state.update { it.copy(session = cur.opponentMove()) }
        playSound(before, m)
    }

    fun retry() { s.session?.let { start(it.puzzle, rated = true) } }

    fun onSquareTap(sq: Int) {
        val ses = s.session ?: return
        if (ses.state != PuzzleSession.State.YOUR_TURN) return
        val pos = ses.position
        val sel = s.selected
        if (sel != null && sq in s.targets) {
            val piece = pos[sel]!!
            val lastRank = if (piece.side == Side.WHITE) 7 else 0
            val want = ses.expected
            val promo = if (piece.type == PieceType.PAWN && sq / 8 == lastRank)
                (want?.takeIf { it.from == sel && it.to == sq }?.promotion ?: PieceType.QUEEN) else null
            tryMove(Move(sel, sq, promo))
            return
        }
        val p = pos[sq]
        if (p != null && p.side == pos.sideToMove) {
            _state.update { it.copy(selected = sq, targets = pos.legalMoves().filter { m -> m.from == sq }.map { m -> m.to }.toSet()) }
        } else _state.update { it.copy(selected = null, targets = emptySet()) }
    }

    private fun tryMove(m: Move) {
        val ses = s.session ?: return
        val before = ses.position
        val (next, outcome) = ses.userMove(m)
        _state.update { it.copy(selected = null, targets = emptySet()) }
        when (outcome) {
            PuzzleSession.Outcome.IGNORED -> Unit
            PuzzleSession.Outcome.CORRECT -> {
                playSound(before, m)
                _state.update { it.copy(session = next, hintSquare = null, message = "Correct, keep going…", good = true) }
                job = scope.launch { delay(500); opponentReply() }
            }
            PuzzleSession.Outcome.SOLVED -> {
                playSound(before, m)
                _state.update { it.copy(session = next, hintSquare = null) }
                val change = rate(solved = true)
                _state.update {
                    it.copy(good = true, message = if (change != null) "Solved! " + delta(change) else "Solved (no rating change after a hint, retry or miss).")
                }
            }
            PuzzleSession.Outcome.WRONG -> {
                sounds.play(BoardSoundManager.Sfx.CHECK)
                val san = runCatching { before.toSan(m) }.getOrDefault(m.uci)
                val change = rate(solved = false)
                _state.update {
                    it.copy(session = next, good = false, message = "$san isn't the solution." + (change?.let { c -> " " + delta(c) } ?: "") +
                        " Retry, see the solution, or go to the next puzzle.")
                }
            }
        }
    }

    /** Shows the piece to move. Using a hint means this puzzle no longer counts as solved. */
    fun hint() {
        val ses = s.session ?: return
        if (ses.state != PuzzleSession.State.YOUR_TURN) return
        val change = rate(solved = false)
        _state.update {
            it.copy(session = ses.markAssisted(), hintSquare = ses.expected?.from, selected = null, targets = emptySet(), good = null,
                message = "Hint: move the highlighted piece." + (change?.let { c -> " (Counts as a miss: ${delta(c)})" } ?: ""))
        }
    }

    /** Plays out the rest of the solution, one move at a time. */
    fun showSolution() {
        val ses = s.session ?: return
        if (ses.state == PuzzleSession.State.SETUP) return
        val change = rate(solved = false)
        job?.cancel()
        job = scope.launch {
            var cur = ses.copy(state = PuzzleSession.State.FAILED, failed = true)
            _state.update { it.copy(session = cur, hintSquare = null, selected = null, targets = emptySet(), good = false,
                message = "Solution" + (change?.let { c -> " (${delta(c)})" } ?: "") + ":") }
            val sans = mutableListOf<String>()
            while (cur.ply < cur.puzzle.moves.size) {
                delay(700)
                val before = cur.position
                val m = Move.fromUci(cur.puzzle.moves[cur.ply])
                sans += runCatching { before.toSan(m) }.getOrDefault(m.uci)
                cur = cur.copy(ply = cur.ply + 1)
                playSound(before, m)
                _state.update { it.copy(session = cur, message = "Solution" + (change?.let { c -> " (${delta(c)})" } ?: "") + ": " + sans.joinToString(" ")) }
            }
        }
    }

    fun resetRating() { scope.launch { settings.resetPuzzles() } }

    private fun rate(solved: Boolean): Double? {
        val ses = s.session ?: return null
        if (s.rated) return null
        val st = s.stats
        val clean = solved && !ses.failed
        val new = PuzzleRating.update(st.rating, ses.puzzle.rating, clean, st.played)
        val change = new - st.rating
        _state.update { it.copy(rated = true, ratingChange = change) }
        scope.launch { settings.recordPuzzle(ses.puzzle.id, new, clean) }
        return change
    }

    private fun delta(c: Double) = (if (c >= 0) "+" else "−") + "%.0f".format(kotlin.math.abs(c)) + " Elo"

    private fun playSound(before: Position, m: Move) {
        val after = runCatching { before.play(m, validate = false) }.getOrNull() ?: return
        sounds.play(
            when {
                after.isCheckmate -> BoardSoundManager.Sfx.GAME_END
                after.inCheck() -> BoardSoundManager.Sfx.CHECK
                before.isCastle(m) -> BoardSoundManager.Sfx.CASTLE
                before.capturedPiece(m) != null -> BoardSoundManager.Sfx.CAPTURE
                else -> BoardSoundManager.Sfx.MOVE
            },
        )
    }
}
