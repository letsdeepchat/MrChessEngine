package com.mrchess.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mrchess.app.data.EngineProvider
import com.mrchess.app.data.GameStore
import com.mrchess.app.data.SavedGame
import com.mrchess.app.vision.ImageToFen
import com.mrchess.app.vision.Recognition
import com.mrchess.core.engine.EngineStrength
import com.mrchess.core.engine.SearchLimit
import com.mrchess.core.explain.Explanation
import com.mrchess.core.review.GameReview
import com.mrchess.core.rules.Game
import com.mrchess.core.rules.GameResult
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Pgn
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { PLAY, IMPORT, REVIEW }
enum class Mode { PLAY, ANALYSIS }

data class BoardState(
    val game: Game = Game(),
    val mode: Mode = Mode.PLAY,
    val userSide: Side = Side.WHITE,
    val elo: Int = 1500,
    val selected: Int? = null,
    val targets: Set<Int> = emptySet(),
    val pendingPromotion: Pair<Int, Int>? = null,
    val engineThinking: Boolean = false,
    val hint: Explanation? = null,
    val hintPosFen: String? = null,
    val hintLoading: Boolean = false,
    val autoAnalyze: Boolean = false,
    val flipped: Boolean = false,
    val hintsUsed: Int = 0,
    val status: String? = null,
    val error: String? = null,
) {
    val position: Position get() = game.current
    val lastMove: Move? get() = game.moves.lastOrNull()
    val isOver: Boolean get() = game.result != GameResult.ONGOING
    val userToMove: Boolean get() = mode == Mode.ANALYSIS || position.sideToMove == userSide
}

data class ImportState(
    val text: String = "",
    val recognizing: Boolean = false,
    val recognition: Recognition? = null,
    val sideToMove: Side = Side.WHITE,
    val blackAtBottom: Boolean = false,
    val error: String? = null,
)

data class ReviewState(
    val saved: List<SavedGame> = emptyList(),
    val game: Game? = null,
    val progress: Pair<Int, Int>? = null,
    val review: GameReview? = null,
    val selectedPly: Int? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val store = GameStore(app)
    private var visionInstance: ImageToFen? = null
    private val vision: ImageToFen get() = visionInstance ?: ImageToFen(ctx).also { visionInstance = it }

    private val _screen = MutableStateFlow(Screen.PLAY)
    val screen: StateFlow<Screen> = _screen
    private val _board = MutableStateFlow(BoardState())
    val board: StateFlow<BoardState> = _board
    private val _import = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _import
    private val _review = MutableStateFlow(ReviewState())
    val review: StateFlow<ReviewState> = _review

    private var engineJob: Job? = null
    private var hintJob: Job? = null
    private var reviewJob: Job? = null

    fun go(s: Screen) {
        _screen.value = s
        if (s == Screen.REVIEW) refreshSaved()
    }

    // ------------------------------------------------------------------ play / analysis

    fun newGame(side: Side, elo: Int) {
        cancelEngineWork()
        val g = Game().apply {
            tags["White"] = if (side == Side.WHITE) "You" else engineLabel(elo)
            tags["Black"] = if (side == Side.BLACK) "You" else engineLabel(elo)
        }
        _board.value = BoardState(game = g, userSide = side, elo = elo, flipped = side == Side.BLACK)
        viewModelScope.launch { runCatching { EngineProvider.engine(ctx).newGame() } }
        maybeEngineMove()
    }

    private fun engineLabel(elo: Int) = if (elo >= 3200) "Stockfish (full)" else "Stockfish $elo"

    fun setElo(elo: Int) = _board.update { it.copy(elo = elo) }

    fun startAnalysis(game: Game) {
        cancelEngineWork()
        _board.value = BoardState(game = game, mode = Mode.ANALYSIS, autoAnalyze = true)
        _screen.value = Screen.PLAY
        requestHint()
    }

    fun playFromHere(fen: String, side: Side) {
        cancelEngineWork()
        val g = Game(fen).apply { tags["White"] = if (side == Side.WHITE) "You" else engineLabel(_board.value.elo); tags["Black"] = if (side == Side.BLACK) "You" else engineLabel(_board.value.elo) }
        _board.value = BoardState(game = g, userSide = side, elo = _board.value.elo, flipped = side == Side.BLACK)
        _screen.value = Screen.PLAY
        maybeEngineMove()
    }

    fun toggleMode() {
        cancelEngineWork()
        _board.update {
            if (it.mode == Mode.PLAY) it.copy(mode = Mode.ANALYSIS, autoAnalyze = true)
            else it.copy(mode = Mode.PLAY, userSide = it.position.sideToMove, autoAnalyze = false, hint = null)
        }
        if (_board.value.autoAnalyze) requestHint()
    }

    fun toggleAutoAnalyze() {
        _board.update { it.copy(autoAnalyze = !it.autoAnalyze) }
        if (_board.value.autoAnalyze) requestHint()
    }

    fun flip() = _board.update { it.copy(flipped = !it.flipped) }

    fun onSquareTap(sq: Int) {
        val s = _board.value
        if (s.engineThinking || s.isOver || !s.userToMove) return
        val pos = s.position
        val sel = s.selected
        if (sel != null && sq in s.targets) {
            val piece = pos[sel]!!
            val promoRank = if (piece.side == Side.WHITE) 7 else 0
            if (piece.type == PieceType.PAWN && sq / 8 == promoRank) {
                _board.update { it.copy(pendingPromotion = sel to sq) }
            } else userMove(Move(sel, sq))
            return
        }
        val p = pos[sq]
        if (p != null && p.side == pos.sideToMove) {
            val targets = pos.legalMoves().filter { it.from == sq }.map { it.to }.toSet()
            _board.update { it.copy(selected = sq, targets = targets) }
        } else _board.update { it.copy(selected = null, targets = emptySet()) }
    }

    fun choosePromotion(type: PieceType?) {
        val pp = _board.value.pendingPromotion ?: return
        _board.update { it.copy(pendingPromotion = null) }
        if (type != null) userMove(Move(pp.first, pp.second, type))
        else _board.update { it.copy(selected = null, targets = emptySet()) }
    }

    /** Plays a move given in UCI form (e.g. tapping "Play hint"). */
    fun playUci(uci: String) {
        val m = runCatching { Move.fromUci(uci) }.getOrNull() ?: return
        if (_board.value.position.isLegal(m) && _board.value.userToMove) userMove(m)
    }

    private fun userMove(m: Move) {
        hintJob?.cancel()
        val g = _board.value.game.copy().play(m)
        _board.update { it.copy(game = g, selected = null, targets = emptySet(), hint = null, hintPosFen = null, status = null) }
        afterMove()
    }

    private fun afterMove() {
        val s = _board.value
        if (s.isOver) { onGameOver(); return }
        if (s.mode == Mode.PLAY) maybeEngineMove()
        if (s.autoAnalyze && _board.value.userToMove) requestHint()
    }

    private fun maybeEngineMove() {
        val s = _board.value
        if (s.mode != Mode.PLAY || s.isOver || s.position.sideToMove == s.userSide) return
        engineJob?.cancel()
        _board.update { it.copy(engineThinking = true) }
        engineJob = viewModelScope.launch {
            try {
                val coach = EngineProvider.coach(ctx)
                val mv = coach.opponentMove(s.position, EngineStrength.fromElo(s.elo))
                if (mv != null) {
                    val g = _board.value.game.copy().play(mv)
                    _board.update { it.copy(game = g, engineThinking = false) }
                    afterMove()
                } else _board.update { it.copy(engineThinking = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _board.update { it.copy(engineThinking = false, error = "Engine error: ${e.message}") }
            }
        }
    }

    fun requestHint() {
        val s = _board.value
        if (s.isOver || s.engineThinking) return
        hintJob?.cancel()
        val fen = s.position.toFen()
        _board.update { it.copy(hintLoading = true, hintsUsed = if (it.mode == Mode.PLAY) it.hintsUsed + 1 else it.hintsUsed) }
        hintJob = viewModelScope.launch {
            try {
                val coach = EngineProvider.coach(ctx)
                val limit = if (s.mode == Mode.ANALYSIS) SearchLimit.MoveTime(2500) else SearchLimit.MoveTime(1500)
                val e = coach.hint(s.position, limit)
                if (_board.value.position.toFen() == fen) _board.update { it.copy(hint = e, hintPosFen = fen, hintLoading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _board.update { it.copy(hintLoading = false, error = "Engine error: ${e.message}") }
            }
        }
    }

    fun undo() {
        cancelEngineWork()
        _board.update {
            val n = if (it.mode == Mode.PLAY) {
                // Take back the engine's reply too, so it's the user's turn again.
                if (it.position.sideToMove == it.userSide) 2 else 1
            } else 1
            it.copy(game = it.game.copy().undo(n), hint = null, selected = null, targets = emptySet(), status = null)
        }
        if (_board.value.autoAnalyze) requestHint()
        else if (_board.value.mode == Mode.PLAY) maybeEngineMove()
    }

    fun resign() {
        val s = _board.value
        if (s.mode != Mode.PLAY || s.isOver) return
        s.game.tags["Result"] = if (s.userSide == Side.WHITE) "0-1" else "1-0"
        s.game.tags["Termination"] = "You resigned"
        _board.update { it.copy(game = it.game.copy(), status = "You resigned.") }
        onGameOver()
    }

    private fun onGameOver() {
        val s = _board.value
        val reason = s.game.resultReason ?: s.game.tags["Termination"] ?: ""
        _board.update { it.copy(status = "Game over: ${s.game.result.pgn} $reason".trim()) }
        if (s.mode == Mode.PLAY && s.game.ply >= 2) {
            s.game.tags["Hints"] = s.hintsUsed.toString()
            viewModelScope.launch(Dispatchers.IO) { store.save(s.game) }
        }
    }

    fun clearError() = _board.update { it.copy(error = null) }

    private fun cancelEngineWork() {
        // Cancelling the coroutine makes UciEngine send "stop" and wait for the engine to settle.
        engineJob?.cancel(); hintJob?.cancel()
        _board.update { it.copy(engineThinking = false, hintLoading = false) }
    }

    // ------------------------------------------------------------------ import

    fun setImportText(t: String) = _import.update { it.copy(text = t, error = null) }

    /** Accepts a FEN, a PGN, or a bare SAN move list. */
    fun importText() {
        val t = _import.value.text.trim()
        if (t.isEmpty()) return
        val looksFen = t.count { it == '/' } == 7 && !t.contains('[') && t.lines().size == 1
        try {
            val game = if (looksFen) {
                val problems = Position.validate(t)
                if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
                Game(Position.fromFen(t).toFen())
            } else Pgn.parse(t)
            startAnalysis(game)
        } catch (e: Exception) {
            _import.update { it.copy(error = e.message ?: "Could not read FEN/PGN") }
        }
    }

    fun importImage(uri: Uri) {
        _screen.value = Screen.IMPORT
        _import.update { it.copy(recognizing = true, recognition = null, error = null) }
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { vision.recognize(uri) }
                _import.update { it.copy(recognizing = false, recognition = r, blackAtBottom = r.likelyBlackAtBottom) }
            } catch (e: Exception) {
                _import.update { it.copy(recognizing = false, error = "Could not recognise a board: ${e.message}") }
            }
        }
    }

    fun setImportSide(side: Side) = _import.update { it.copy(sideToMove = side) }
    fun setBlackAtBottom(v: Boolean) = _import.update { it.copy(blackAtBottom = v) }

    fun recognizedFen(): String? {
        val s = _import.value
        val r = s.recognition ?: return null
        return r.toFen(s.sideToMove, s.blackAtBottom)
    }

    fun analyzeRecognized() {
        val fen = recognizedFen() ?: return
        val problems = Position.validate(fen)
        if (problems.isNotEmpty()) { _import.update { it.copy(error = problems.joinToString("; ")) }; return }
        startAnalysis(Game(fen))
    }

    fun editRecognizedAsText() {
        val fen = recognizedFen() ?: return
        _import.update { it.copy(text = fen, recognition = null) }
    }

    // ------------------------------------------------------------------ review

    fun refreshSaved() {
        viewModelScope.launch(Dispatchers.IO) { _review.update { it.copy(saved = store.list()) } }
    }

    fun deleteSaved(g: SavedGame) {
        viewModelScope.launch(Dispatchers.IO) { store.delete(g.file); _review.update { it.copy(saved = store.list()) } }
    }

    fun reviewCurrentGame() = startReview(_board.value.game.copy())

    fun reviewSaved(g: SavedGame) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.load(g.file) }.onSuccess { startReview(it) }
                .onFailure { e -> _review.update { it.copy(error = e.message) } }
        }
    }

    fun reviewPgn(text: String) = runCatching { startReview(Pgn.parse(text)) }
        .onFailure { e -> _import.update { it.copy(error = e.message) } }

    private fun startReview(game: Game) {
        cancelEngineWork()
        reviewJob?.cancel()
        _screen.value = Screen.REVIEW
        _review.update { it.copy(game = game, review = null, progress = 0 to game.positions.size, selectedPly = null, error = null) }
        reviewJob = viewModelScope.launch {
            try {
                val coach = EngineProvider.coach(ctx)
                val r = coach.review(game, SearchLimit.Depth(14)) { done, total ->
                    _review.update { it.copy(progress = done to total) }
                }
                _review.update { it.copy(review = r, progress = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _review.update { it.copy(progress = null, error = "Review failed: ${e.message}") }
            }
        }
    }

    fun selectReviewPly(ply: Int?) = _review.update { it.copy(selectedPly = ply) }

    fun closeReview() {
        reviewJob?.cancel()
        _review.update { it.copy(game = null, review = null, progress = null, selectedPly = null) }
        refreshSaved()
    }

    override fun onCleared() {
        EngineProvider.shutdown()
        visionInstance?.close()
    }
}
