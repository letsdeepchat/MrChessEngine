package com.mrchess.core.rules

/**
 * Immutable chess position with full legal move generation (castling, en passant, promotion),
 * FEN I/O, SAN I/O and attack queries used by the move explainer.
 */
class Position private constructor(
    private val board: Array<Piece?>,
    val sideToMove: Side,
    val castling: Int,
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    operator fun get(sq: Int): Piece? = board[sq]

    fun pieces(): List<Pair<Int, Piece>> = (0 until 64).mapNotNull { sq -> board[sq]?.let { sq to it } }

    fun kingSquare(side: Side): Int =
        (0 until 64).firstOrNull { board[it]?.let { p -> p.type == PieceType.KING && p.side == side } == true }
            ?: Sq.NONE

    // ---------------------------------------------------------------- attacks

    /** Squares attacked by the piece standing on [sq] (pseudo attacks, ignoring pins). */
    fun attacksFrom(sq: Int): List<Int> {
        val p = board[sq] ?: return emptyList()
        val f = Sq.file(sq)
        val r = Sq.rank(sq)
        val out = ArrayList<Int>(16)
        when (p.type) {
            PieceType.PAWN -> {
                val dr = if (p.side == Side.WHITE) 1 else -1
                for (df in intArrayOf(-1, 1)) if (Sq.onBoard(f + df, r + dr)) out += Sq.of(f + df, r + dr)
            }
            PieceType.KNIGHT -> for ((df, dr) in KNIGHT_DELTAS) if (Sq.onBoard(f + df, r + dr)) out += Sq.of(f + df, r + dr)
            PieceType.KING -> for ((df, dr) in KING_DELTAS) if (Sq.onBoard(f + df, r + dr)) out += Sq.of(f + df, r + dr)
            else -> for ((df, dr) in sliderDirs(p.type)) {
                var nf = f + df
                var nr = r + dr
                while (Sq.onBoard(nf, nr)) {
                    val t = Sq.of(nf, nr)
                    out += t
                    if (board[t] != null) break
                    nf += df; nr += dr
                }
            }
        }
        return out
    }

    /** All squares of [by]'s pieces that attack [sq]. */
    fun attackersOf(sq: Int, by: Side): List<Int> {
        val out = ArrayList<Int>(4)
        for (from in 0 until 64) {
            val p = board[from] ?: continue
            if (p.side != by) continue
            if (attacksSquare(from, p, sq)) out += from
        }
        return out
    }

    fun isAttacked(sq: Int, by: Side): Boolean {
        for (from in 0 until 64) {
            val p = board[from] ?: continue
            if (p.side == by && attacksSquare(from, p, sq)) return true
        }
        return false
    }

    private fun attacksSquare(from: Int, p: Piece, target: Int): Boolean {
        val df = Sq.file(target) - Sq.file(from)
        val dr = Sq.rank(target) - Sq.rank(from)
        return when (p.type) {
            PieceType.PAWN -> dr == (if (p.side == Side.WHITE) 1 else -1) && (df == 1 || df == -1)
            PieceType.KNIGHT -> (df * df + dr * dr) == 5
            PieceType.KING -> from != target && df in -1..1 && dr in -1..1
            PieceType.BISHOP -> df != 0 && (df == dr || df == -dr) && clearPath(from, target)
            PieceType.ROOK -> (df == 0) != (dr == 0) && clearPath(from, target)
            PieceType.QUEEN -> from != target && (df == 0 || dr == 0 || df == dr || df == -dr) && clearPath(from, target)
        }
    }

    private fun clearPath(from: Int, to: Int): Boolean {
        val sf = Integer.signum(Sq.file(to) - Sq.file(from))
        val sr = Integer.signum(Sq.rank(to) - Sq.rank(from))
        var f = Sq.file(from) + sf
        var r = Sq.rank(from) + sr
        while (Sq.of(f, r) != to) {
            if (board[Sq.of(f, r)] != null) return false
            f += sf; r += sr
        }
        return true
    }

    fun inCheck(side: Side = sideToMove): Boolean {
        val k = kingSquare(side)
        return k != Sq.NONE && isAttacked(k, side.opposite)
    }

    // ---------------------------------------------------------------- move generation

    private var legalCache: List<Move>? = null

    fun legalMoves(): List<Move> {
        legalCache?.let { return it }
        val moves = pseudoLegalMoves().filter { m -> !play(m, validate = false).inCheck(sideToMove) }
        legalCache = moves
        return moves
    }

    fun isLegal(m: Move): Boolean = m in legalMoves()

    private fun pseudoLegalMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        val us = sideToMove
        for (from in 0 until 64) {
            val p = board[from] ?: continue
            if (p.side != us) continue
            val f = Sq.file(from)
            val r = Sq.rank(from)
            when (p.type) {
                PieceType.PAWN -> genPawn(from, f, r, us, out)
                PieceType.KNIGHT, PieceType.KING -> {
                    val deltas = if (p.type == PieceType.KNIGHT) KNIGHT_DELTAS else KING_DELTAS
                    for ((df, dr) in deltas) {
                        if (!Sq.onBoard(f + df, r + dr)) continue
                        val t = Sq.of(f + df, r + dr)
                        val q = board[t]
                        if (q == null || q.side != us) out += Move(from, t)
                    }
                    if (p.type == PieceType.KING) genCastles(from, us, out)
                }
                else -> for ((df, dr) in sliderDirs(p.type)) {
                    var nf = f + df
                    var nr = r + dr
                    while (Sq.onBoard(nf, nr)) {
                        val t = Sq.of(nf, nr)
                        val q = board[t]
                        if (q == null) out += Move(from, t)
                        else {
                            if (q.side != us) out += Move(from, t)
                            break
                        }
                        nf += df; nr += dr
                    }
                }
            }
        }
        return out
    }

    private fun genPawn(from: Int, f: Int, r: Int, us: Side, out: MutableList<Move>) {
        val dir = if (us == Side.WHITE) 1 else -1
        val startRank = if (us == Side.WHITE) 1 else 6
        val promoRank = if (us == Side.WHITE) 7 else 0
        fun add(to: Int) {
            if (Sq.rank(to) == promoRank) {
                for (t in PROMOS) out += Move(from, to, t)
            } else out += Move(from, to)
        }
        val one = Sq.of(f, r + dir)
        if (Sq.onBoard(f, r + dir) && board[one] == null) {
            add(one)
            val two = Sq.of(f, r + 2 * dir)
            if (r == startRank && board[two] == null) out += Move(from, two)
        }
        for (df in intArrayOf(-1, 1)) {
            if (!Sq.onBoard(f + df, r + dir)) continue
            val t = Sq.of(f + df, r + dir)
            val q = board[t]
            if ((q != null && q.side != us) || t == epSquare) add(t)
        }
    }

    private fun genCastles(from: Int, us: Side, out: MutableList<Move>) {
        val home = if (us == Side.WHITE) 4 else 60
        if (from != home || inCheck(us)) return
        val them = us.opposite
        val (kRight, qRight) = if (us == Side.WHITE) Castling.WK to Castling.WQ else Castling.BK to Castling.BQ
        if (castling and kRight != 0 && board[home + 1] == null && board[home + 2] == null &&
            board[home + 3]?.let { it.type == PieceType.ROOK && it.side == us } == true &&
            !isAttacked(home + 1, them) && !isAttacked(home + 2, them)
        ) out += Move(home, home + 2)
        if (castling and qRight != 0 && board[home - 1] == null && board[home - 2] == null && board[home - 3] == null &&
            board[home - 4]?.let { it.type == PieceType.ROOK && it.side == us } == true &&
            !isAttacked(home - 1, them) && !isAttacked(home - 2, them)
        ) out += Move(home, home - 2)
    }

    // ---------------------------------------------------------------- make move

    fun isCapture(m: Move): Boolean = board[m.to] != null || isEnPassant(m)

    fun isEnPassant(m: Move): Boolean =
        board[m.from]?.type == PieceType.PAWN && m.to == epSquare && Sq.file(m.from) != Sq.file(m.to)

    fun isCastle(m: Move): Boolean =
        board[m.from]?.type == PieceType.KING && kotlin.math.abs(Sq.file(m.to) - Sq.file(m.from)) == 2

    /** Piece captured by [m], including en passant. */
    fun capturedPiece(m: Move): Piece? =
        if (isEnPassant(m)) Piece(PieceType.PAWN, sideToMove.opposite) else board[m.to]

    /** Returns the position after [m]. Throws if [m] is illegal (when validate = true). */
    fun play(m: Move, validate: Boolean = true): Position {
        if (validate) require(isLegal(m)) { "Illegal move ${m.uci} in ${toFen()}" }
        val b = board.copyOf()
        val p = b[m.from] ?: error("No piece on ${Sq.name(m.from)}")
        val captured = b[m.to]
        var cr = castling
        var ep = Sq.NONE

        if (isEnPassant(m)) b[Sq.of(Sq.file(m.to), Sq.rank(m.from))] = null
        if (isCastle(m)) {
            val rank = Sq.rank(m.from)
            if (Sq.file(m.to) == 6) { b[Sq.of(5, rank)] = b[Sq.of(7, rank)]; b[Sq.of(7, rank)] = null }
            else { b[Sq.of(3, rank)] = b[Sq.of(0, rank)]; b[Sq.of(0, rank)] = null }
        }
        b[m.to] = if (m.promotion != null) Piece(m.promotion, p.side) else p
        b[m.from] = null

        if (p.type == PieceType.PAWN && kotlin.math.abs(m.to - m.from) == 16) ep = (m.from + m.to) / 2
        if (p.type == PieceType.KING) cr = cr and if (p.side == Side.WHITE) (Castling.WK or Castling.WQ).inv() else (Castling.BK or Castling.BQ).inv()
        for (sq in intArrayOf(m.from, m.to)) cr = cr and when (sq) {
            0 -> Castling.WQ.inv(); 7 -> Castling.WK.inv(); 56 -> Castling.BQ.inv(); 63 -> Castling.BK.inv(); else -> 0xF
        }
        val half = if (p.type == PieceType.PAWN || captured != null) 0 else halfmoveClock + 1
        val full = if (sideToMove == Side.BLACK) fullmoveNumber + 1 else fullmoveNumber
        return Position(b, sideToMove.opposite, cr, ep, half, full)
    }

    /** Same position with the other side to move (used to ask "what does the opponent threaten?"). */
    fun nullMove(): Position = Position(board.copyOf(), sideToMove.opposite, castling, Sq.NONE, halfmoveClock, fullmoveNumber)

    fun withSideToMove(side: Side): Position =
        if (side == sideToMove) this else Position(board.copyOf(), side, castling, Sq.NONE, halfmoveClock, fullmoveNumber)

    // ---------------------------------------------------------------- game state

    val isCheckmate: Boolean get() = legalMoves().isEmpty() && inCheck()
    val isStalemate: Boolean get() = legalMoves().isEmpty() && !inCheck()

    val isInsufficientMaterial: Boolean
        get() {
            val nonKings = pieces().filter { it.second.type != PieceType.KING }
            if (nonKings.isEmpty()) return true
            if (nonKings.size == 1 && nonKings[0].second.type in setOf(PieceType.BISHOP, PieceType.KNIGHT)) return true
            if (nonKings.all { it.second.type == PieceType.BISHOP }) {
                val colours = nonKings.map { (Sq.file(it.first) + Sq.rank(it.first)) % 2 }.toSet()
                return colours.size == 1
            }
            return false
        }

    /** Material balance in centipawns from White's point of view. */
    fun materialBalance(): Int = pieces().sumOf { (_, p) ->
        if (p.type == PieceType.KING) 0 else if (p.side == Side.WHITE) p.type.value else -p.type.value
    }

    /** Key for threefold repetition (FEN without move counters). */
    val repetitionKey: String get() = toFen().split(' ').take(4).joinToString(" ")

    // ---------------------------------------------------------------- FEN

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[Sq.of(f, r)]
                if (p == null) empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p.fenChar)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (sideToMove == Side.WHITE) " w " else " b ")
        var c = ""
        if (castling and Castling.WK != 0) c += "K"
        if (castling and Castling.WQ != 0) c += "Q"
        if (castling and Castling.BK != 0) c += "k"
        if (castling and Castling.BQ != 0) c += "q"
        sb.append(c.ifEmpty { "-" })
        // Only print the e.p. square when a capture is actually possible (matches most GUIs / Stockfish).
        val epPrintable = epSquare != Sq.NONE && pseudoEpCapturePossible()
        sb.append(' ').append(if (epPrintable) Sq.name(epSquare) else "-")
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    private fun pseudoEpCapturePossible(): Boolean {
        val dir = if (sideToMove == Side.WHITE) -1 else 1
        val r = Sq.rank(epSquare) + dir
        val f = Sq.file(epSquare)
        return intArrayOf(f - 1, f + 1).any { nf ->
            Sq.onBoard(nf, r) && board[Sq.of(nf, r)] == Piece(PieceType.PAWN, sideToMove)
        }
    }

    // ---------------------------------------------------------------- SAN

    fun toSan(m: Move): String {
        val p = board[m.from] ?: error("No piece on ${Sq.name(m.from)}")
        val san = StringBuilder()
        if (isCastle(m)) {
            san.append(if (Sq.file(m.to) == 6) "O-O" else "O-O-O")
        } else {
            if (p.type == PieceType.PAWN) {
                if (isCapture(m)) san.append('a' + Sq.file(m.from)).append('x')
                san.append(Sq.name(m.to))
                m.promotion?.let { san.append('=').append(it.letter.uppercaseChar()) }
            } else {
                san.append(p.type.letter.uppercaseChar())
                val rivals = legalMoves().filter {
                    it.to == m.to && it.from != m.from && board[it.from]?.type == p.type
                }
                if (rivals.isNotEmpty()) {
                    val sameFile = rivals.any { Sq.file(it.from) == Sq.file(m.from) }
                    val sameRank = rivals.any { Sq.rank(it.from) == Sq.rank(m.from) }
                    when {
                        !sameFile -> san.append('a' + Sq.file(m.from))
                        !sameRank -> san.append('1' + Sq.rank(m.from))
                        else -> san.append(Sq.name(m.from))
                    }
                }
                if (isCapture(m)) san.append('x')
                san.append(Sq.name(m.to))
            }
        }
        val after = play(m, validate = false)
        if (after.inCheck()) san.append(if (after.legalMoves().isEmpty()) '#' else '+')
        return san.toString()
    }

    fun parseSan(sanIn: String): Move {
        val clean = sanIn.trim().replace("0-0-0", "O-O-O").replace("0-0", "O-O").trimEnd('+', '#', '!', '?')
        legalMoves().firstOrNull { toSan(it).trimEnd('+', '#') == clean }?.let { return it }
        // Lenient fallbacks: missing '=' in promotions ("e8Q"), or UCI notation.
        legalMoves().firstOrNull { toSan(it).trimEnd('+', '#').replace("=", "") == clean.replace("=", "") }?.let { return it }
        runCatching { Move.fromUci(clean.lowercase()) }.getOrNull()?.takeIf { isLegal(it) }?.let { return it }
        throw IllegalArgumentException("Illegal or unparseable move '$sanIn' in ${toFen()}")
    }

    /** Converts a UCI line (e.g. an engine PV) into SAN, stopping at the first illegal move. */
    fun uciLineToSan(uciMoves: List<String>): List<String> {
        var pos = this
        val out = ArrayList<String>()
        for (u in uciMoves) {
            val m = runCatching { Move.fromUci(u) }.getOrNull() ?: break
            if (!pos.isLegal(m)) break
            out += pos.toSan(m)
            pos = pos.play(m, validate = false)
        }
        return out
    }

    override fun equals(other: Any?): Boolean = other is Position && other.toFen() == toFen()
    override fun hashCode(): Int = toFen().hashCode()
    override fun toString(): String = toFen()

    fun ascii(): String = buildString {
        for (r in 7 downTo 0) {
            for (f in 0..7) append(board[Sq.of(f, r)]?.fenChar ?: '.').append(' ')
            append('\n')
        }
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        private val KNIGHT_DELTAS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        private val KING_DELTAS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        private val DIAG = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val ORTHO = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val PROMOS = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)

        fun sliderDirs(t: PieceType) = when (t) {
            PieceType.BISHOP -> DIAG
            PieceType.ROOK -> ORTHO
            PieceType.QUEEN -> DIAG + ORTHO
            else -> emptyList()
        }

        fun start(): Position = fromFen(START_FEN)

        /** Parses a FEN. Missing trailing fields (common in screenshots/tools) default sensibly. */
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.isNotEmpty() && parts[0].isNotEmpty()) { "Empty FEN" }
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN must have 8 ranks: $fen" }
            val b = arrayOfNulls<Piece>(64)
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (c in row) {
                    if (c.isDigit()) f += c - '0'
                    else {
                        require(f < 8) { "Rank too long in FEN: $row" }
                        b[Sq.of(f, r)] = Piece.fromFen(c) ?: throw IllegalArgumentException("Bad piece '$c' in FEN")
                        f++
                    }
                }
                require(f == 8) { "Rank '$row' does not have 8 files" }
            }
            val side = if (parts.getOrNull(1) == "b") Side.BLACK else Side.WHITE
            var cr = 0
            parts.getOrNull(2)?.forEach {
                when (it) { 'K' -> cr = cr or Castling.WK; 'Q' -> cr = cr or Castling.WQ; 'k' -> cr = cr or Castling.BK; 'q' -> cr = cr or Castling.BQ }
            }
            // Drop castling rights that the piece placement contradicts.
            fun has(sq: Int, t: PieceType, s: Side) = b[sq]?.let { it.type == t && it.side == s } == true
            if (!has(4, PieceType.KING, Side.WHITE)) cr = cr and (Castling.WK or Castling.WQ).inv()
            if (!has(7, PieceType.ROOK, Side.WHITE)) cr = cr and Castling.WK.inv()
            if (!has(0, PieceType.ROOK, Side.WHITE)) cr = cr and Castling.WQ.inv()
            if (!has(60, PieceType.KING, Side.BLACK)) cr = cr and (Castling.BK or Castling.BQ).inv()
            if (!has(63, PieceType.ROOK, Side.BLACK)) cr = cr and Castling.BK.inv()
            if (!has(56, PieceType.ROOK, Side.BLACK)) cr = cr and Castling.BQ.inv()
            val ep = parts.getOrNull(3)?.takeIf { it != "-" }?.let { runCatching { Sq.parse(it) }.getOrNull() } ?: Sq.NONE
            val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            return Position(b, side, cr, ep, half, full)
        }

        /** Returns a list of human-readable problems, or empty if the FEN is a sane, playable position. */
        fun validate(fen: String): List<String> {
            val pos = runCatching { fromFen(fen) }.getOrElse { return listOf(it.message ?: "Invalid FEN") }
            val problems = mutableListOf<String>()
            val ps = pos.pieces()
            for (s in Side.entries) {
                val kings = ps.count { it.second.type == PieceType.KING && it.second.side == s }
                if (kings != 1) problems += "${s.label} must have exactly one king (found $kings)"
                val pawns = ps.count { it.second.type == PieceType.PAWN && it.second.side == s }
                if (pawns > 8) problems += "${s.label} has $pawns pawns"
            }
            if (ps.any { it.second.type == PieceType.PAWN && (Sq.rank(it.first) == 0 || Sq.rank(it.first) == 7) })
                problems += "Pawns cannot stand on the first or last rank"
            if (problems.isEmpty() && pos.inCheck(pos.sideToMove.opposite))
                problems += "${pos.sideToMove.opposite.label} is in check but it is ${pos.sideToMove.label}'s move"
            return problems
        }
    }
}
