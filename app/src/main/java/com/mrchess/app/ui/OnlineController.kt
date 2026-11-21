package com.mrchess.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import com.mrchess.app.audio.BoardSoundManager
import com.mrchess.app.data.Connectivity
import com.mrchess.app.data.ReviewRepository
import com.mrchess.app.data.SettingsRepository
import com.mrchess.core.online.Elo
import com.mrchess.core.online.MatchEvent
import com.mrchess.core.online.OnlineGame
import com.mrchess.core.online.OnlineIdentity
import com.mrchess.core.online.OnlinePlay
import com.mrchess.core.online.RatingFilter
import com.mrchess.core.online.TimeControl
import com.mrchess.core.rules.GameResult
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class OnlinePhase { LOBBY, CONNECTING, SEARCHING, PLAYING, FINISHED }

@Immutable
data class OnlineUiState(
    val phase: OnlinePhase = OnlinePhase.LOBBY,
    val timeControl: TimeControl = TimeControl.BLITZ_3,
    val filter: RatingFilter = RatingFilter.PM_200,
    val rating: Int = Elo.ONLINE_START,
    val searchRange: IntRange? = null,
    val game: OnlineGame? = null,
    val mySide: Side = Side.WHITE,
    /** The game as a timeline (built from the move list) for the board, strip and review. */
    val timeline: GameTimeline = GameTimeline(),
    /** elapsedRealtime when the side to move started thinking (null: clocks not running yet). */
    val turnStartedAt: Long? = null,
    val selected: Int? = null,
    val targets: Set<Int> = emptySet(),
    val pendingPromotion: Pair<Int, Int>? = null,
    /** A move sent but not yet confirmed by the server. */
    val sending: Boolean = false,
    val ratingChange: Int? = null,
    val message: String? = null,
) {
    val myTurn: Boolean get() = game?.let { !it.finished && it.sideToMove == mySide } == true && !sending
    val opponentSide: Side get() = mySide.opposite
    val drawOfferedToMe: Boolean get() = game?.let { it.drawOfferBy != null && it.drawOfferBy != it.uidOf(mySide) } == true
    val drawOfferedByMe: Boolean get() = game?.let { it.drawOfferBy == it.uidOf(mySide) } == true
    /** Before both sides have moved the game can be aborted without a rating change. */
    val canAbort: Boolean get() = (game?.moves?.size ?: 0) < 2

    /** Remaining time of [side] right now. */
    fun clockMs(side: Side, now: Long): Long {
        val g = game ?: return timeControl.initialMs
        val stored = g.msOf(side)
        val start = turnStartedAt
        return if (!g.finished && start != null && g.sideToMove == side) (stored - (now - start)).coerceAtLeast(0) else stored
    }
}

/**
 * Live online games: lobby (time control + rating filter), matchmaking, the board with both
 * clocks, resign / draw / abort, and the rating update. All state is in [OnlineGame] on
 * Firestore; this controller only mirrors it and sends this player's actions.
 */
class OnlineController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val online: OnlinePlay,
    private val settings: SettingsRepository,
    private val sounds: BoardSoundManager,
    private val history: ReviewRepository,
    private val username: StateFlow<String>,
    private val onReview: (GameTimeline, String) -> Unit,
    private val onRatingChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(
        OnlineUiState(
            timeControl = TimeControl.byId(settings.onlineTimeControl),
            filter = RatingFilter.byId(settings.onlineFilter),
            rating = settings.onlineElo.value,
        ),
    )
    val state: StateFlow<OnlineUiState> = _state
    private val s get() = _state.value
    val available: Boolean get() = online.available

    private var searchJob: Job? = null
    private var gameJob: Job? = null
    private var clockJob: Job? = null
    private var uid: String? = null

    init {
        scope.launch { settings.onlineElo.collect { r -> _state.update { it.copy(rating = r) } } }
    }

    fun setTimeControl(tc: TimeControl) { settings.onlineTimeControl = tc.id; _state.update { it.copy(timeControl = tc) } }
    fun setFilter(f: RatingFilter) { settings.onlineFilter = f.id; _state.update { it.copy(filter = f) } }
    fun clearMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ matchmaking

    fun findMatch() {
        if (searchJob?.isActive == true) return
        if (!online.available) { _state.update { it.copy(message = "Online play isn't set up in this build.") }; return }
        if (!Connectivity.isOnline(context)) { _state.update { it.copy(message = Connectivity.OFFLINE_MESSAGE) }; return }
        _state.update { it.copy(phase = OnlinePhase.CONNECTING, message = null, ratingChange = null) }
        searchJob = scope.launch {
            val id = online.ensureSignedIn().getOrElse { e ->
                _state.update { it.copy(phase = OnlinePhase.LOBBY, message = e.message ?: "Couldn't connect.") }
                return@launch
            }
            uid = id
            val me = OnlineIdentity(id, username.value, settings.onlineElo.value)
            online.findMatch(me, s.timeControl, s.filter).collect { ev ->
                when (ev) {
                    is MatchEvent.Searching -> _state.update { it.copy(phase = OnlinePhase.SEARCHING, searchRange = ev.range) }
                    is MatchEvent.Matched -> join(ev.gameId)
                    is MatchEvent.Failed -> _state.update { it.copy(phase = OnlinePhase.LOBBY, message = ev.message) }
                }
            }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        if (s.phase == OnlinePhase.SEARCHING || s.phase == OnlinePhase.CONNECTING) _state.update { it.copy(phase = OnlinePhase.LOBBY, searchRange = null) }
    }

    private fun join(gameId: String) {
        gameJob?.cancel()
        _state.update { it.copy(phase = OnlinePhase.PLAYING, searchRange = null, game = null, timeline = GameTimeline(), turnStartedAt = null) }
        gameJob = scope.launch {
            try {
                online.observeGame(gameId).collect(::onSnapshot)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(message = "Lost the connection to the game: ${e.message}") }
            }
        }
        startClockWatch()
    }

    /** Back to the lobby after a finished game. */
    fun leaveGame() {
        gameJob?.cancel(); clockJob?.cancel()
        _state.update { it.copy(phase = OnlinePhase.LOBBY, game = null, timeline = GameTimeline(), selected = null, targets = emptySet(), ratingChange = null) }
    }

    // ------------------------------------------------------------------ game updates

    private fun onSnapshot(g: OnlineGame) {
        val me = uid ?: return
        val prev = s.game
        val mySide = g.sideOf(me) ?: return
        val movesChanged = prev == null || prev.moves != g.moves
        val timeline = if (!movesChanged) s.timeline else buildTimeline(g, mySide)
        val now = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                game = g, mySide = mySide, timeline = timeline, sending = false,
                // Clocks run once White has moved; each turn is timed from when this phone saw it begin.
                turnStartedAt = when {
                    g.moves.isEmpty() || g.finished -> null
                    movesChanged -> now
                    else -> it.turnStartedAt ?: now
                },
                selected = if (movesChanged) null else it.selected,
                targets = if (movesChanged) emptySet() else it.targets,
            )
        }
        if (movesChanged && prev != null && g.moves.size > prev.moves.size) {
            val node = timeline.currentNode
            // Our own move already made its sound when it was played.
            if (node != null && node.mover != mySide) sounds.playFor(node, timeline.result != GameResult.ONGOING)
        }
        if (g.finished && prev?.finished != true) onFinished(g, mySide, timeline)
    }

    private fun buildTimeline(g: OnlineGame, mySide: Side): GameTimeline {
        val opp = mySide.opposite
        var t = GameTimeline(
            g.startFen,
            tags = mapOf(
                "Event" to "Online ${TimeControl.byId(g.timeControl).label}",
                "White" to if (mySide == Side.WHITE) "You" else "@${g.whiteName}",
                "Black" to if (mySide == Side.BLACK) "You" else "@${g.blackName}",
                "WhiteElo" to g.whiteRating.toString(), "BlackElo" to g.blackRating.toString(),
                "Site" to "Mr. Chess Trainer online", "Opponent" to g.nameOf(opp),
            ),
        )
        for (uci in g.moves) t = runCatching { t.play(Move.fromUci(uci)) }.getOrElse { return t }
        return t
    }

    private fun onFinished(g: OnlineGame, mySide: Side, t0: GameTimeline) {
        clockJob?.cancel()
        val result = g.result ?: "*"
        var t = t0.withTag("Result", result)
        g.termination?.let { t = t.withTag("Termination", it) }
        val aborted = result == "*"
        var change: Int? = null
        if (!aborted && settings.markOnlineGameRated(g.id)) {
            val score = when (result) {
                "1/2-1/2" -> 0.5
                "1-0" -> if (mySide == Side.WHITE) 1.0 else 0.0
                else -> if (mySide == Side.BLACK) 1.0 else 0.0
            }
            val before = settings.onlineElo.value
            val after = Elo.update(before, g.ratingOf(mySide.opposite), score)
            settings.setOnlineElo(after, settings.onlineGames + 1)
            change = after - before
            onRatingChanged()
            if (t.nodes.size >= 2) scope.launch(Dispatchers.IO) { runCatching { history.save(t, "ONLINE") } }
        }
        _state.update { it.copy(phase = OnlinePhase.FINISHED, timeline = t, ratingChange = change ?: it.ratingChange, selected = null, targets = emptySet()) }
        sounds.play(BoardSoundManager.Sfx.GAME_END)
    }

    /** Flags whoever ran out of time (the opponent gets a short grace period for network lag). */
    private fun startClockWatch() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                delay(250)
                val st = s
                val g = st.game ?: continue
                if (g.finished || st.turnStartedAt == null) continue
                val side = g.sideToMove
                val left = st.clockMs(side, SystemClock.elapsedRealtime())
                val grace = if (side == st.mySide) 0L else FLAG_GRACE_MS
                if (left <= 0 && SystemClock.elapsedRealtime() - st.turnStartedAt >= g.msOf(side) + grace) {
                    val winner = side.opposite
                    online.finish(g.id, g.moves.size, if (winner == Side.WHITE) "1-0" else "0-1", "${side.label} lost on time")
                    delay(2_000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ player input

    fun onSquareTap(sq: Int) {
        val st = s
        if (!st.myTurn) return
        val pos = st.timeline.position
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

    private fun play(m: Move) {
        val st = s
        val g = st.game ?: return
        if (!st.timeline.position.isLegal(m)) return
        val ply = g.moves.size
        val now = SystemClock.elapsedRealtime()
        val tc = TimeControl.byId(g.timeControl)
        val spent = st.turnStartedAt?.let { now - it } ?: 0L
        val remaining = (g.msOf(st.mySide) - spent).coerceAtLeast(0) + if (ply >= 1) tc.incrementMs else 0
        val next = st.timeline.toEnd().play(m)
        val res = next.result
        val (result, termination) = when {
            res == GameResult.ONGOING -> null to null
            else -> res.pgn to (next.resultReasonAtCurrent() ?: "Game over")
        }
        // Optimistic: show the move at once; the server snapshot confirms it.
        _state.update { it.copy(timeline = next, selected = null, targets = emptySet(), sending = true) }
        next.currentNode?.let { sounds.playFor(it, res != GameResult.ONGOING) }
        scope.launch {
            online.sendMove(g.id, ply, m.uci, remaining, result, termination).onFailure { e ->
                _state.update { it.copy(sending = false, timeline = buildTimeline(g, it.mySide), message = "Move not sent: ${e.message}") }
            }
        }
    }

    fun resign() {
        val g = s.game ?: return
        val loser = s.mySide
        scope.launch { online.finish(g.id, g.moves.size, if (loser == Side.WHITE) "0-1" else "1-0", "${loser.label} resigned").onFailure(::report) }
    }

    fun abort() {
        val g = s.game ?: return
        scope.launch { online.finish(g.id, g.moves.size, "*", "Game aborted").onFailure(::report) }
    }

    fun offerDraw() { val g = s.game ?: return; scope.launch { online.offerDraw(g.id, true).onFailure(::report) } }
    fun declineDraw() { val g = s.game ?: return; scope.launch { online.offerDraw(g.id, false).onFailure(::report) } }
    fun acceptDraw() {
        val g = s.game ?: return
        if (!s.drawOfferedToMe) return
        scope.launch { online.finish(g.id, g.moves.size, "1/2-1/2", "Draw by agreement").onFailure(::report) }
    }

    /** Opens the finished game in Review (annotations for both sides + Game Summary). */
    fun review() {
        val t = s.timeline
        val g = s.game ?: return
        onReview(t, "${g.whiteName} vs ${g.blackName}")
    }

    private fun report(e: Throwable) = _state.update { it.copy(message = e.message ?: "Something went wrong") }

    companion object {
        private const val FLAG_GRACE_MS = 2_000L
    }
}
