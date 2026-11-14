package com.mrchess.core.rules

enum class GameResult(val pgn: String) { WHITE_WINS("1-0"), BLACK_WINS("0-1"), DRAW("1/2-1/2"), ONGOING("*") }

/** A game: a start position plus a list of moves, with derived positions and SAN. */
class Game(
    val startFen: String = Position.START_FEN,
    moves: List<Move> = emptyList(),
    val tags: MutableMap<String, String> = linkedMapOf(),
) {
    private val _moves = ArrayList<Move>()
    private val _positions = arrayListOf(Position.fromFen(startFen))
    private val _san = ArrayList<String>()

    init { moves.forEach { play(it) } }

    val moves: List<Move> get() = _moves
    val positions: List<Position> get() = _positions
    val sanMoves: List<String> get() = _san
    val current: Position get() = _positions.last()
    val ply: Int get() = _moves.size

    fun play(m: Move): Game {
        val pos = current
        require(pos.isLegal(m)) { "Illegal move ${m.uci}" }
        _san += pos.toSan(m)
        _moves += m
        _positions += pos.play(m, validate = false)
        return this
    }

    fun playSan(san: String): Game = play(current.parseSan(san))

    fun undo(count: Int = 1): Game {
        repeat(count.coerceAtMost(_moves.size)) {
            _moves.removeAt(_moves.lastIndex)
            _san.removeAt(_san.lastIndex)
            _positions.removeAt(_positions.lastIndex)
        }
        return this
    }

    fun copy(): Game = Game(startFen, _moves.toList(), LinkedHashMap(tags))

    val isThreefold: Boolean
        get() = _positions.groupingBy { it.repetitionKey }.eachCount().values.any { it >= 3 }

    val result: GameResult
        get() {
            val pos = current
            return when {
                pos.isCheckmate -> if (pos.sideToMove == Side.WHITE) GameResult.BLACK_WINS else GameResult.WHITE_WINS
                pos.isStalemate || pos.isInsufficientMaterial || pos.halfmoveClock >= 100 || isThreefold -> GameResult.DRAW
                else -> tags["Result"]?.let { r -> GameResult.entries.firstOrNull { it.pgn == r } } ?: GameResult.ONGOING
            }
        }

    val resultReason: String?
        get() {
            val pos = current
            return when {
                pos.isCheckmate -> "Checkmate"
                pos.isStalemate -> "Stalemate"
                pos.isInsufficientMaterial -> "Insufficient material"
                pos.halfmoveClock >= 100 -> "50-move rule"
                isThreefold -> "Threefold repetition"
                else -> null
            }
        }

    fun toPgn(): String = Pgn.write(this)
}
