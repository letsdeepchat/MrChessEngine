package com.mrchess.core.rules

import com.mrchess.core.engine.Score
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
    fun positionAt(ply: Int): Position = Position.fromFen(fenAt(ply))
    val position: Position get() = positionAt(currentPly)

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
    fun resultAtCurrent(): GameResult {
        val g = toGame(currentPly)
        if (!atEnd) g.tags.remove("Result")
        return g.result
    }

    /** "1. e4", "e5", "2. Nf3" … labels for the move strip. */
    fun moveLabel(index: Int): String {
        val start = Position.fromFen(startFen)
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
