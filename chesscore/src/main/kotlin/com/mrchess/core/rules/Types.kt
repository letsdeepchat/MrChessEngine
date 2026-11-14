package com.mrchess.core.rules

enum class Side {
    WHITE, BLACK;

    val opposite: Side get() = if (this == WHITE) BLACK else WHITE
    val label: String get() = if (this == WHITE) "White" else "Black"
}

enum class PieceType(val letter: Char, val value: Int, val label: String) {
    PAWN('p', 100, "pawn"),
    KNIGHT('n', 320, "knight"),
    BISHOP('b', 330, "bishop"),
    ROOK('r', 500, "rook"),
    QUEEN('q', 900, "queen"),
    KING('k', 20_000, "king");

    val isSlider: Boolean get() = this == BISHOP || this == ROOK || this == QUEEN

    companion object {
        fun fromLetter(c: Char): PieceType? = entries.firstOrNull { it.letter == c.lowercaseChar() }
    }
}

data class Piece(val type: PieceType, val side: Side) {
    val fenChar: Char get() = if (side == Side.WHITE) type.letter.uppercaseChar() else type.letter

    /** Unicode glyph; the UI uses the solid glyph set for both colours and tints it. */
    val glyph: Char
        get() = when (type) {
            PieceType.KING -> '♚'
            PieceType.QUEEN -> '♛'
            PieceType.ROOK -> '♜'
            PieceType.BISHOP -> '♝'
            PieceType.KNIGHT -> '♞'
            PieceType.PAWN -> '♟'
        }

    companion object {
        fun fromFen(c: Char): Piece? {
            val t = PieceType.fromLetter(c) ?: return null
            return Piece(t, if (c.isUpperCase()) Side.WHITE else Side.BLACK)
        }
    }
}

/** Squares are 0..63 with a1 = 0, h1 = 7, a8 = 56, h8 = 63. */
object Sq {
    const val NONE = -1
    fun file(sq: Int) = sq and 7
    fun rank(sq: Int) = sq ushr 3
    fun of(file: Int, rank: Int) = rank * 8 + file
    fun onBoard(file: Int, rank: Int) = file in 0..7 && rank in 0..7
    fun name(sq: Int): String = "${'a' + file(sq)}${'1' + rank(sq)}"
    fun parse(s: String): Int {
        require(s.length == 2) { "Bad square: $s" }
        val f = s[0] - 'a'
        val r = s[1] - '1'
        require(onBoard(f, r)) { "Bad square: $s" }
        return of(f, r)
    }
}

data class Move(val from: Int, val to: Int, val promotion: PieceType? = null) {
    val uci: String get() = Sq.name(from) + Sq.name(to) + (promotion?.letter ?: "")

    override fun toString() = uci

    companion object {
        fun fromUci(s: String): Move {
            require(s.length == 4 || s.length == 5) { "Bad UCI move: $s" }
            val promo = if (s.length == 5) PieceType.fromLetter(s[4]) else null
            return Move(Sq.parse(s.substring(0, 2)), Sq.parse(s.substring(2, 4)), promo)
        }
    }
}

object Castling {
    const val WK = 1
    const val WQ = 2
    const val BK = 4
    const val BQ = 8
}
