package com.mrchess.app.ui

import android.content.Context
import com.mrchess.app.audio.BoardSoundManager
import com.mrchess.app.data.EngineProvider
import com.mrchess.app.data.ReviewRepository
import com.mrchess.core.TimelineAnnotator
import com.mrchess.core.engine.EngineStrength
import com.mrchess.core.engine.SearchLimit
import com.mrchess.core.explain.Explanation
import com.mrchess.core.review.GameSummary
import com.mrchess.core.review.MoveQuality
import com.mrchess.core.rules.GameResult
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SessionMode { PLAY_AI, TWO_PLAYER, IMPORT_ANALYSIS, POST_GAME_REVIEW }

data class WorkspaceState(
    val mode: SessionMode,
    val timeline: GameTimeline = GameTimeline(),
    val userSide: Side = Side.WHITE,
    val elo: Int = 1500,
    val flipped: Boolean = false,
    /** Two Player only: turn the board 180° after every move so the side to move is at the bottom. */
    val autoFlip: Boolean = mode == SessionMode.TWO_PLAYER,
    /** Free moves for both sides without engine replies (always on for import/review). */
    val analysisMode: Boolean = mode == SessionMode.IMPORT_ANALYSIS || mode == SessionMode.POST_GAME_REVIEW,
    val liveCoach: Boolean = false,
    val selected: Int? = null,
    val targets: Set<Int> = emptySet(),
    val pendingPromotion: Pair<Int, Int>? = null,
    val engineThinking: Boolean = false,
    val hint: Explanation? = null,
    val hintFen: String? = null,
    val hintLoading: Boolean = false,
    /** Whether the floating coach card is shown (dismissing it keeps Live coach running). */
    val cardOpen: Boolean = false,
    /** Index of a played move being inspected from the summary (board shows the position before it). */
    val insightIndex: Int? = null,
    val showSummary: Boolean = false,
    val annotating: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val title: String = "",
) {
    val position: Position get() = timeline.position
    val result: GameResult get() = timeline.resultAtCurrent()
    val gameOver: Boolean get() = result != GameResult.ONGOING
    val currentHint: Explanation? get() = hint?.takeIf { hintFen == position.toFen() }
    val inspecting: Boolean get() = insightIndex != null && insightIndex == timeline.currentPly && insightIndex < timeline.nodes.size
    val summary: GameSummary get() = GameSummary.of(timeline.nodes.map { it.mover to it.annotation })
    val engineToMove: Boolean
        get() = mode == SessionMode.PLAY_AI && !analysisMode && position.sideToMove != userSide
    val canMovePieces: Boolean get() = !engineThinking && !gameOver && !engineToMove
}

/**
 * State and engine work for one board session. All engine work for a session runs in one worker
 * coroutine in priority order (opponent move → coach hint → move-quality annotation), so it never
 * fights itself for the single Stockfish process; any user action cancels and reschedules it.
 */
class WorkspaceController(
    private val context: Context,
    private val scope: CoroutineScope,
    mode: SessionMode,
    private val sounds: BoardSoundManager,
    private val history: ReviewRepository,
    private val onSaved: () -> Unit = {},
) {
    private val _state = MutableStateFlow(WorkspaceState(mode, title = defaultTitle(mode, 1500)))
    val state: StateFlow<WorkspaceState> = _state
    private val s get() = _state.value

    private var worker: Job? = null
    private var active = false
    private var hintRequested = false
    private var summaryWhenDone = false
    /** Room id of this game's history record, once it has been saved. */
    private var recordId: String? = null
    private var annotator: TimelineAnnotator? = null
    private val annotationDepth = if (mode == SessionMode.POST_GAME_REVIEW) 14 else 12

    // ------------------------------------------------------------------ lifecycle

    fun setActive(a: Boolean) {
        active = a
        if (a) reschedule() else worker?.cancel()
    }

    fun load(timeline: GameTimeline, title: String? = null, recordId: String? = null) {
        _state.update {
            it.copy(
                timeline = timeline, selected = null, targets = emptySet(), hint = null, hintFen = null,
                insightIndex = null, showSummary = false, status = null, title = title ?: it.title,
                flipped = timeline.tags["Black"] == "You",
            )
        }
        this.recordId = recordId
        summaryWhenDone = s.mode == SessionMode.POST_GAME_REVIEW
        reschedule()
    }

    fun newGame(side: Side, elo: Int) {
        val engine = engineLabel(elo)
        val t = GameTimeline(tags = mapOf("White" to if (side == Side.WHITE) "You" else engine, "Black" to if (side == Side.BLACK) "You" else engine))
        _state.value = WorkspaceState(
            mode = s.mode, timeline = t, userSide = side, elo = elo, flipped = side == Side.BLACK,
            liveCoach = s.liveCoach, cardOpen = s.liveCoach, autoFlip = s.autoFlip, title = defaultTitle(s.mode, elo),
        )
        recordId = null
        summaryWhenDone = false
        scope.launch { runCatching { EngineProvider.engine(context).newGame() } }
        reschedule()
    }

    fun newTwoPlayerGame() {
        val t = GameTimeline(tags = mapOf("White" to "Player 1", "Black" to "Player 2"))
        _state.value = WorkspaceState(s.mode, timeline = t, autoFlip = s.autoFlip, liveCoach = s.liveCoach, cardOpen = s.liveCoach, title = "Two Player")
        recordId = null
        reschedule()
    }

    // ------------------------------------------------------------------ board input

    fun onSquareTap(sq: Int) {
        val st = s
        if (!st.canMovePieces) return
        val pos = st.position
        val sel = st.selected
        if (sel != null && sq in st.targets) {
            val piece = pos[sel]!!
            val promoRank = if (piece.side == Side.WHITE) 7 else 0
            if (piece.type == PieceType.PAWN && sq / 8 == promoRank) _state.update { it.copy(pendingPromotion = sel to sq) }
            else play(Move(sel, sq))
            return
        }
        val p = pos[sq]
        if (p != null && p.side == pos.sideToMove) {
            _state.update { it.copy(selected = sq, targets = pos.legalMoves().filter { m -> m.from == sq }.map { m -> m.to }.toSet()) }
        } else _state.update { it.copy(selected = null, targets = emptySet()) }
    }

    fun choosePromotion(type: PieceType?) {
        val pp = s.pendingPromotion ?: return
        _state.update { it.copy(pendingPromotion = null, selected = if (type == null) null else it.selected) }
        if (type != null) play(Move(pp.first, pp.second, type))
    }

    fun playUci(uci: String) {
        val m = runCatching { Move.fromUci(uci) }.getOrNull() ?: return
        if (s.canMovePieces && s.position.isLegal(m)) play(m)
    }

    /** Plays a move at the current ply; earlier plies branch (later moves are dropped). */
    private fun play(m: Move) {
        val t = s.timeline.play(m)
        applyTimeline(t, playSound = true)
        if (s.mode == SessionMode.TWO_PLAYER && s.autoFlip) {
            // Let the move land visually, then turn the board to the player whose turn it is.
            scope.launch {
                delay(250)
                _state.update { if (it.autoFlip) it.copy(flipped = it.position.sideToMove == Side.BLACK) else it }
            }
        }
        if (s.gameOver && s.timeline.atEnd) onGameOver()
        reschedule()
    }

    private fun applyTimeline(t: GameTimeline, playSound: Boolean) {
        _state.update {
            it.copy(timeline = t, selected = null, targets = emptySet(), status = null, insightIndex = null)
        }
        if (playSound) t.currentNode?.let { sounds.playFor(it, s.gameOver) }
    }

    // ------------------------------------------------------------------ navigation

    fun toStart() = navigate(s.timeline.toStart())
    fun back() = navigate(s.timeline.back())
    fun forward() = navigate(s.timeline.forward(), sound = true)
    fun toEnd() = navigate(s.timeline.toEnd())
    fun goTo(ply: Int) = navigate(s.timeline.goTo(ply))

    private fun navigate(t: GameTimeline, sound: Boolean = false) {
        if (t.currentPly == s.timeline.currentPly) return
        applyTimeline(t, playSound = sound)
        if (s.mode == SessionMode.TWO_PLAYER && s.autoFlip) _state.update { it.copy(flipped = it.position.sideToMove == Side.BLACK) }
        reschedule()
    }

    /** From the summary: show the position before move [index] with the played and best moves. */
    fun inspect(index: Int) {
        _state.update {
            it.copy(timeline = it.timeline.goTo(index), insightIndex = index, cardOpen = true, showSummary = false, selected = null, targets = emptySet())
        }
        reschedule()
    }

    // ------------------------------------------------------------------ toggles & actions

    fun flip() = _state.update { it.copy(flipped = !it.flipped) }
    /**
     * Top-bar rotate button. Two Player: toggles auto-flip (turning it on immediately faces the
     * side to move; turning it off flips the board once). Other modes: flips the board.
     */
    fun rotateButton() = _state.update {
        when {
            it.mode != SessionMode.TWO_PLAYER -> it.copy(flipped = !it.flipped)
            !it.autoFlip -> it.copy(autoFlip = true, flipped = it.position.sideToMove == Side.BLACK)
            else -> it.copy(autoFlip = false, flipped = !it.flipped)
        }
    }

    fun toggleAnalysisMode() {
        if (s.mode != SessionMode.PLAY_AI) return
        _state.update { it.copy(analysisMode = !it.analysisMode) }
        if (!s.analysisMode) _state.update { it.copy(userSide = if (it.engineToMove) it.position.sideToMove else it.userSide) }
        reschedule()
    }

    fun toggleLiveCoach() {
        _state.update { it.copy(liveCoach = !it.liveCoach, cardOpen = !it.liveCoach || it.cardOpen) }
        reschedule()
    }

    /** "Best move + why": compute (if needed) and open the card. */
    fun requestHint() {
        _state.update { it.copy(cardOpen = true, insightIndex = null) }
        if (s.currentHint == null) { hintRequested = true; reschedule() }
    }

    fun dismissCard() = _state.update { it.copy(cardOpen = false) }
    fun showCard() = _state.update { it.copy(cardOpen = true) }
    fun openSummary() = _state.update { it.copy(showSummary = true) }
    fun closeSummary() = _state.update { it.copy(showSummary = false) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun setElo(elo: Int) = _state.update { it.copy(elo = elo) }

    fun resign() {
        if (s.mode != SessionMode.PLAY_AI || s.gameOver) return
        val t = s.timeline.toEnd().withTag("Result", if (s.userSide == Side.WHITE) "0-1" else "1-0").withTag("Termination", "You resigned")
        _state.update { it.copy(timeline = t) }
        onGameOver()
        reschedule()
    }

    private fun onGameOver() {
        val st = s
        val reason = st.timeline.toGame(st.timeline.currentPly).resultReason ?: st.timeline.tags["Termination"] ?: ""
        _state.update { it.copy(status = "Game over: ${st.result.pgn} $reason".trim()) }
        summaryWhenDone = true
        if ((st.mode == SessionMode.PLAY_AI || st.mode == SessionMode.TWO_PLAYER) && st.timeline.nodes.size >= 2) saveHistory()
    }

    /** Saves (or updates) this game in the Review history with whatever analysis is done so far. */
    private fun saveHistory() {
        val t = s.timeline
        val mode = s.mode.name
        scope.launch(Dispatchers.IO) {
            runCatching { recordId = history.save(t, mode, recordId); onSaved() }
        }
    }

    // ------------------------------------------------------------------ engine worker

    private fun reschedule() {
        if (!active) return
        worker?.cancel()
        worker = scope.launch {
            try {
                runWork()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(engineThinking = false, hintLoading = false, annotating = false, error = "Engine error: ${e.message}") }
            }
        }
    }

    private suspend fun runWork() {
        val coach = EngineProvider.coach(context)
        // 1. Opponent move (Play mode, at the end of the line, engine's turn).
        val st = s
        if (st.engineToMove && st.timeline.atEnd && !st.gameOver) {
            _state.update { it.copy(engineThinking = true) }
            val mv = try {
                coach.opponentMove(st.position, EngineStrength.fromElo(st.elo))
            } finally {
                _state.update { it.copy(engineThinking = false) }
            }
            if (mv != null && s.timeline === st.timeline) {
                applyTimeline(s.timeline.play(mv), playSound = true)
                if (s.gameOver) onGameOver()
            }
        }
        // 2. Coach hint for the current position.
        val now = s
        val hintAllowed = !now.gameOver && !(now.engineToMove && now.timeline.atEnd)
        if (hintAllowed && (hintRequested || now.liveCoach) && now.currentHint == null && !now.inspecting) {
            _state.update { it.copy(hintLoading = true) }
            val fen = now.position.toFen()
            val limit = if (now.analysisMode || now.mode != SessionMode.PLAY_AI) SearchLimit.MoveTime(2500) else SearchLimit.MoveTime(1500)
            val e = try { coach.hint(now.position, limit) } finally { _state.update { it.copy(hintLoading = false) } }
            hintRequested = false
            if (s.position.toFen() == fen) _state.update { it.copy(hint = e, hintFen = fen) }
        }
        // 3. Move-quality badges: the move just played first, then the rest of the game in order.
        val ann = annotator ?: TimelineAnnotator(coach, SearchLimit.Depth(annotationDepth)).also { annotator = it }
        val t0 = s.timeline
        val order = buildList {
            t0.currentNode?.let { add(t0.currentPly - 1) }
            addAll(t0.nodes.indices)
        }.distinct().filter { t0.nodes[it].annotation == null }
        if (order.isNotEmpty()) {
            _state.update { it.copy(annotating = true) }
            try {
                for (i in order) {
                    val node = s.timeline.nodes.getOrNull(i) ?: break
                    if (node.annotation != null) continue
                    val a = ann.annotate(s.timeline, i)
                    _state.update { cur ->
                        if (cur.timeline.nodes.getOrNull(i)?.uci == node.uci) cur.copy(timeline = cur.timeline.annotate(i, a)) else cur
                    }
                }
            } finally {
                _state.update { it.copy(annotating = false) }
            }
        }
        val allDone = s.timeline.nodes.isNotEmpty() && s.timeline.nodes.all { it.annotation != null }
        // Keep the history record's accuracy and badges up to date once analysis finishes.
        if (allDone && order.isNotEmpty() && (recordId != null || s.mode == SessionMode.POST_GAME_REVIEW)) saveHistory()
        if (summaryWhenDone && allDone) {
            summaryWhenDone = false
            _state.update { it.copy(showSummary = true) }
        }
    }

    companion object {
        fun engineLabel(elo: Int) = if (elo >= 3200) "Stockfish (full)" else "Stockfish $elo"

        fun defaultTitle(mode: SessionMode, elo: Int) = when (mode) {
            SessionMode.PLAY_AI -> "vs ${eloLabel(elo)}"
            SessionMode.TWO_PLAYER -> "Two Player"
            SessionMode.IMPORT_ANALYSIS -> "Imported position"
            SessionMode.POST_GAME_REVIEW -> "Game review"
        }

        fun eloLabel(elo: Int) = when {
            elo >= 3200 -> "Stockfish full (3200+)"
            elo < 950 -> "Beginner bot (~800)"
            elo < EngineStrength.MIN_ELO -> "Casual bot (~1100)"
            else -> "Stockfish $elo"
        }

        /** First move index with a given quality for a side, for summary jumps. */
        fun firstIndex(st: WorkspaceState, side: Side, q: MoveQuality): Int? = st.summary.firstIndex[side]?.get(q)
    }
}
