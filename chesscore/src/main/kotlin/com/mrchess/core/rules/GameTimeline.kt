package com.mrchess.core.rules

import com.mrchess.core.engine.Score
import com.mrchess.core.review.GameSummary
import com.mrchess.core.review.MoveAnnotation
import com.mrchess.core.review.MoveQuality

/** One played move in the timeline, plus whatever the engine has found out about it. */
data class MoveNode(
    val uci: String,
    val san: String,
    val fenAfterMove: String,
    val capturedPiece: Piece?,
    val mover: Side,
    val isCastle: Boolean,
    val annotation: MoveAnnotation? = null,
) {
    val move: Move get() = Move.fromUci(uci)
    val evalScore: Score? get() = annotation?.evalAfterWhite
    val bestMoveSan: String? get() = annotation?.bestMoveSan
    val rationaleText: String? get() = annotation?.rationale
    val moveQualityBadge: MoveQuality? get() = annotation?.quality
}

/**
 * Immutable game history with a cursor. Ply 0 is the start (or imported) position; ply N is after
 * N moves. Moving back keeps the later moves for "next"; playing a different move from an earlier
 * ply truncates them (branching), playing the same move simply steps forward.
 */
data class GameTimeline(
    val startFen: String = Position.START_FEN,
    val nodes: List<MoveNode> = emptyList(),
    val currentPly: Int = 0,
    val tags: Map<String, String> = emptyMap(),
) {
    init { require(currentPly in 0..nodes.size) { "ply $currentPly out of range" } }

    val lastPly: Int get() = nodes.size
    val canBack: Boolean get() = currentPly > 0
    val canForward: Boolean get() = currentPly < nodes.size
    val atEnd: Boolean get() = currentPly == nodes.size

    fun fenAt(ply: Int): String = if (ply == 0) startFen else nodes[ply - 1].fenAfterMove

    /** Every position of the line: index 0 is the start (or `[FEN]` tag) position, index i the board after ply i. */
    val fenTimeline: List<String> get() = List(nodes.size + 1) { fenAt(it) }
    fun positionAt(ply: Int): Position = if (ply == currentPly) position else Position.fromFen(fenAt(ply))

    // Derived values are computed once per timeline instance. The UI reads them many times per
    // frame, and the instance is shared by every UI state that has not changed the game itself.
    /** The current position (parsed once; its legal moves are cached on the instance). */
    val position: Position by lazy(LazyThreadSafetyMode.PUBLICATION) { PositionCache.get(fenAt(currentPly)) }
    private val startPosition: Position by lazy(LazyThreadSafetyMode.PUBLICATION) { Position.fromFen(startFen) }
    /** Result at the current ply (see [resultAtCurrent]). */
    val result: GameResult by lazy(LazyThreadSafetyMode.PUBLICATION) { computeResult() }
    /** Move-quality counts and accuracy of the annotated moves. */
    val summary: GameSummary by lazy(LazyThreadSafetyMode.PUBLICATION) { GameSummary.of(nodes.map { it.mover to it.annotation }) }

    /** The move that led to the current position, if any. */
    val currentNode: MoveNode? get() = if (currentPly == 0) null else nodes[currentPly - 1]

    fun goTo(ply: Int) = copy(currentPly = ply.coerceIn(0, nodes.size))
    fun back() = goTo(currentPly - 1)
    fun forward() = goTo(currentPly + 1)
    fun toStart() = goTo(0)
    fun toEnd() = goTo(nodes.size)

    /** Plays [m] at the current ply (branching if it differs from the stored continuation). */
    fun play(m: Move): GameTimeline {
        if (canForward && nodes[currentPly].uci == m.uci) return forward()
        val pos = position
        require(pos.isLegal(m)) { "Illegal move ${m.uci}" }
        val node = MoveNode(
            uci = m.uci,
            san = pos.toSan(m),
            fenAfterMove = pos.play(m, validate = false).toFen(),
            capturedPiece = pos.capturedPiece(m),
            mover = pos.sideToMove,
            isCastle = pos.isCastle(m),
        )
        return copy(nodes = nodes.take(currentPly) + node, currentPly = currentPly + 1)
    }

    fun annotate(index: Int, a: MoveAnnotation?): GameTimeline =
        if (index !in nodes.indices) this else copy(nodes = nodes.toMutableList().also { it[index] = it[index].copy(annotation = a) })

    fun withTag(k: String, v: String) = copy(tags = tags + (k to v))

    /** A Game with the moves up to [upTo] (default: the whole line), for results and PGN. */
    fun toGame(upTo: Int = nodes.size): Game =
        Game(startFen, nodes.take(upTo).map { it.move }, LinkedHashMap(tags))

    /** Result at the current ply (checkmate, stalemate, repetition, …) or the stored tag at the end. */
    fun resultAtCurrent(): GameResult = result

    /** Why the game ended at the current ply, or null (resignations come from the Termination tag). */
    fun resultReasonAtCurrent(): String? {
        val pos = position
        return when {
            pos.isCheckmate -> "Checkmate"
            pos.isStalemate -> "Stalemate"
            pos.isInsufficientMaterial -> "Insufficient material"
            pos.halfmoveClock >= 100 -> "50-move rule"
            threefoldUpTo(currentPly) -> "Threefold repetition"
            else -> null
        }
    }

    // Same rules as Game.result, without replaying (and re-validating) every move from the start.
    private fun computeResult(): GameResult {
        val pos = position
        return when {
            pos.isCheckmate -> if (pos.sideToMove == Side.WHITE) GameResult.BLACK_WINS else GameResult.WHITE_WINS
            pos.isStalemate || pos.isInsufficientMaterial || pos.halfmoveClock >= 100 || threefoldUpTo(currentPly) -> GameResult.DRAW
            atEnd -> tags["Result"]?.let { r -> GameResult.entries.firstOrNull { it.pgn == r } } ?: GameResult.ONGOING
            else -> GameResult.ONGOING
        }
    }

    /** Any position (board, side, castling, e.p.) seen three times in plies 0..[ply]. */
    private fun threefoldUpTo(ply: Int): Boolean {
        if (ply < 8) return false
        val counts = HashMap<String, Int>()
        counts[startPosition.repetitionKey] = 1
        for (i in 0 until ply) {
            val k = repetitionKey(nodes[i].fenAfterMove)
            val n = (counts[k] ?: 0) + 1
            if (n >= 3) return true
            counts[k] = n
        }
        return false
    }

    private fun repetitionKey(fen: String): String {
        var spaces = 0
        for (i in fen.indices) if (fen[i] == ' ' && ++spaces == 4) return fen.substring(0, i)
        return fen
    }

    /** "1. e4", "e5", "2. Nf3" … labels for the move strip. */
    fun moveLabel(index: Int): String {
        val start = startPosition
        val offset = index + if (start.sideToMove == Side.WHITE) 0 else 1
        val no = start.fullmoveNumber + offset / 2
        val san = nodes[index].san
        return when {
            offset % 2 == 0 -> "$no. $san"
            index == 0 -> "$no… $san"
            else -> san
        }
    }

    companion object {
        fun fromGame(game: Game, atEnd: Boolean = true): GameTimeline {
            var t = GameTimeline(game.startFen, tags = game.tags.toMap())
            for (m in game.moves) t = t.play(m)
            return if (atEnd) t else t.toStart()
        }
    }
}

/**
 * A few recently parsed positions, by FEN. Annotating a move or toggling a flag creates a new
 * timeline instance for the same position; reusing the parsed [Position] keeps its identity, so
 * the board (which keys its animation and drawing on the position) does not redraw or re-slide.
 */
internal object PositionCache {
    private const val SIZE = 16
    private val map = object : LinkedHashMap<String, Position>(SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Position>?) = size > SIZE
    }

    fun get(fen: String): Position = synchronized(map) { map[fen] } ?: Position.fromFen(fen).also { p -> synchronized(map) { map[fen] = p } }
}
