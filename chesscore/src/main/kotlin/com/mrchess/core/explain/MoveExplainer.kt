package com.mrchess.core.explain

import com.mrchess.core.engine.Score
import com.mrchess.core.engine.SearchInfo
import com.mrchess.core.engine.SearchResult
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Piece
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import com.mrchess.core.rules.Sq

enum class Tag(val label: String) {
    MATE("Forced mate"),
    ONLY_MOVE("Only strong move"),
    WINS_MATERIAL("Wins material"),
    CAPTURE("Capture"),
    CHECK("Check"),
    FORK("Fork"),
    PIN("Pin"),
    SKEWER("Skewer"),
    DISCOVERED_ATTACK("Discovered attack"),
    PROMOTION("Promotion"),
    CASTLING("Castling"),
    ESCAPES_CHECK("Escapes check"),
    SAVES_PIECE("Saves a piece"),
    PREVENTS_THREAT("Prevents a threat"),
    DEVELOPMENT("Development"),
    CENTER("Center control"),
    OPEN_FILE("Open file"),
    PASSED_PAWN("Passed pawn"),
    POSITIONAL("Positional"),
}

data class Alternative(val san: String, val score: Score, val deltaCp: Int)

data class Explanation(
    val move: Move,
    val san: String,
    /** Score from the mover's point of view. */
    val score: Score,
    /** Score from White's point of view, for the eval bar. */
    val scoreWhite: Score,
    val headline: String,
    val reasons: List<String>,
    val tags: List<Tag>,
    /** Principal variation in SAN, starting with [san]. */
    val line: List<String>,
    val alternative: Alternative?,
) {
    fun lineText(startPos: Position, plies: Int = 3): String {
        var no = startPos.fullmoveNumber
        var white = startPos.sideToMove == Side.WHITE
        val sb = StringBuilder()
        for ((i, s) in line.take(plies).withIndex()) {
            if (white) sb.append("$no. ") else if (i == 0) sb.append("$no... ")
            sb.append(s).append(' ')
            if (!white) no++
            white = !white
        }
        return sb.toString().trim()
    }
}

/**
 * Rule-based explanation of an engine move. The engine decides *what* is best; this class
 * looks at the board before/after and at the PV/MultiPV to say *why*, in coaching language.
 */
object MoveExplainer {

    private const val ONLY_MOVE_CP = 150

    fun explain(pos: Position, result: SearchResult): Explanation? {
        val best = result.best ?: return null
        val alt = result.second
        return explain(pos, best, alt)
    }

    fun explain(pos: Position, best: SearchInfo, second: SearchInfo? = null): Explanation? {
        val move = best.pv.firstOrNull()?.let { runCatching { Move.fromUci(it) }.getOrNull() } ?: return null
        if (!pos.isLegal(move)) return null
        val us = pos.sideToMove
        val them = us.opposite
        val after = pos.play(move)
        val mover = pos[move.from]!!
        val movedNow = after[move.to]!!
        val san = pos.toSan(move)
        val line = pos.uciLineToSan(best.pv)

        val reasons = mutableListOf<String>()
        val tags = linkedSetOf<Tag>()

        // 1. Mate.
        best.score.mate?.let { m ->
            if (m > 0) {
                tags += Tag.MATE
                reasons += if (m == 1) "Checkmate!" else "Forces checkmate in $m moves. Follow the line: ${shortLine(pos, line, m * 2 - 1)}."
            } else {
                reasons += "Every move gets mated, but this one resists longest (mate in ${-m})."
            }
        }

        // 2. Escaping check.
        if (pos.inCheck()) {
            tags += Tag.ESCAPES_CHECK
            reasons += when {
                mover.type == PieceType.KING -> "Your king is in check; stepping to ${Sq.name(move.to)} is the safest escape."
                pos.isCapture(move) && pos.attackersOf(pos.kingSquare(us), them).contains(move.to) ->
                    "Gets out of check by capturing the checking piece."
                else -> "Gets out of check by blocking the attack."
            }
        }

        // 3. Captures and material won along the main line.
        val captured = pos.capturedPiece(move)
        val materialGain = materialSwingAlongPv(pos, best.pv, us)
        if (captured != null) {
            tags += Tag.CAPTURE
            val defended = after.attackersOf(move.to, them).isNotEmpty()
            reasons += when {
                !defended -> "Captures a free ${captured.type.label} on ${Sq.name(move.to)} — it was undefended."
                captured.type.value > mover.type.value + 50 ->
                    "Wins the exchange: your ${mover.type.label} takes a more valuable ${captured.type.label}."
                else -> "Captures the ${captured.type.label} on ${Sq.name(move.to)}; recapturing doesn't help them."
            }
        }
        if (materialGain >= 150 && Tag.MATE !in tags) {
            tags += Tag.WINS_MATERIAL
            reasons += "The main line wins about ${materialWords(materialGain)}."
        }

        // 4. Promotion.
        move.promotion?.let {
            tags += Tag.PROMOTION
            reasons += "Promotes the pawn to a ${it.label}."
        }

        // 5. Check.
        if (after.inCheck() && !after.isCheckmate) {
            tags += Tag.CHECK
            if (Tag.MATE !in tags) reasons += "Gives check, so ${them.label} must respond to the threat on the king."
        }

        // 6. Tactical motifs created by the moved piece.
        detectFork(after, move.to, movedNow)?.let { tags += Tag.FORK; reasons += it }
        detectPinOrSkewer(after, move.to, movedNow).forEach { (tag, text) -> tags += tag; reasons += text }
        detectDiscovered(pos, after, move)?.let { tags += Tag.DISCOVERED_ATTACK; reasons += it }

        // 7. Defence: saving an attacked piece or parrying a threat.
        if (!pos.inCheck()) {
            if (isHanging(pos, move.from) && !isHanging(after, move.to) && captured == null) {
                tags += Tag.SAVES_PIECE
                reasons += "Your ${mover.type.label} on ${Sq.name(move.from)} was under attack; this moves it to safety."
            }
            val threatBefore = biggestThreat(pos.nullMove())
            val threatAfter = biggestThreat(after)
            if (threatBefore != null && Tag.SAVES_PIECE !in tags &&
                (threatAfter == null || threatAfter.value < threatBefore.value - 100)
            ) {
                tags += Tag.PREVENTS_THREAT
                reasons += "Stops ${them.label}'s threat: ${threatBefore.description}."
            }
        }

        // 8. Castling.
        if (pos.isCastle(move)) {
            tags += Tag.CASTLING
            reasons += "Castles: tucks the king away and brings the rook toward the centre."
        }

        // 9. Positional ideas (only when nothing sharper was found, to keep the explanation focused).
        if (tags.none { it in TACTICAL }) {
            positionalReasons(pos, after, move, mover).forEach { (t, r) -> tags += t; reasons += r }
        }

        // 10. Comparison with the second-best move (MultiPV 2).
        var alternative: Alternative? = null
        if (second != null) {
            val altMove = second.pv.firstOrNull()?.let { runCatching { Move.fromUci(it) }.getOrNull() }
            if (altMove != null && pos.isLegal(altMove)) {
                val delta = cpDelta(best.score, second.score)
                alternative = Alternative(pos.toSan(altMove), second.score, delta)
                if (delta > ONLY_MOVE_CP) {
                    tags += Tag.ONLY_MOVE
                    reasons += "This is the only strong move: the next best, ${alternative.san}, is worse by ${deltaWords(delta)}."
                } else if (delta <= 20) {
                    reasons += "${alternative.san} is about as good — both moves keep the same evaluation."
                }
            }
        }

        // Opponent's expected reply from the PV.
        if (line.size >= 2 && Tag.MATE !in tags) {
            reasons += "Expected continuation: ${shortLine(pos, line, 3)}."
        }

        if (reasons.isEmpty() || tags.isEmpty()) {
            tags += Tag.POSITIONAL
            reasons.add(0, "A quiet improving move; the engine prefers it because it keeps the best balance of activity and safety.")
        }

        val scoreWhite = if (us == Side.WHITE) best.score else best.score.negate()
        return Explanation(
            move = move,
            san = san,
            score = best.score,
            scoreWhite = scoreWhite,
            headline = headline(tags.toList(), san),
            reasons = reasons,
            tags = tags.toList().sortedBy { it.ordinal },
            line = line,
            alternative = alternative,
        )
    }

    private val TACTICAL = setOf(
        Tag.MATE, Tag.WINS_MATERIAL, Tag.CAPTURE, Tag.FORK, Tag.PIN, Tag.SKEWER,
        Tag.DISCOVERED_ATTACK, Tag.PROMOTION, Tag.SAVES_PIECE, Tag.PREVENTS_THREAT, Tag.ESCAPES_CHECK,
    )

    private fun headline(tags: List<Tag>, san: String): String {
        val main = tags.firstOrNull { it != Tag.ONLY_MOVE && it != Tag.CHECK && it != Tag.CAPTURE }
            ?: tags.firstOrNull() ?: Tag.POSITIONAL
        return "$san — ${main.label}" + if (Tag.ONLY_MOVE in tags && main != Tag.ONLY_MOVE) " (only move)" else ""
    }

    fun cpDelta(best: Score, other: Score): Int {
        val b = best.comparable
        val o = other.comparable
        // Clamp mate scores so "mate vs +5" reads as a large but finite delta.
        return (b.coerceIn(-2000, 2000) - o.coerceIn(-2000, 2000)).coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- tactics

    /** True if the piece on [sq] can be won: attacked and undefended, or attacked by something cheaper. */
    fun isHanging(pos: Position, sq: Int): Boolean {
        val p = pos[sq] ?: return false
        if (p.type == PieceType.KING) return false
        val attackers = pos.attackersOf(sq, p.side.opposite)
        if (attackers.isEmpty()) return false
        val defenders = pos.attackersOf(sq, p.side)
        if (defenders.isEmpty()) return true
        val cheapest = attackers.minOf { pos[it]!!.type.value }
        return cheapest < p.type.value
    }

    private fun detectFork(after: Position, sq: Int, piece: Piece): String? {
        val them = piece.side.opposite
        if (isHanging(after, sq) && !after.inCheck()) return null
        val targets = after.attacksFrom(sq).mapNotNull { t ->
            val q = after[t] ?: return@mapNotNull null
            if (q.side != them) return@mapNotNull null
            val valuable = q.type == PieceType.KING || q.type.value > piece.type.value ||
                after.attackersOf(t, them).isEmpty() && q.type != PieceType.PAWN
            if (valuable) t to q else null
        }
        if (targets.size < 2) return null
        val names = targets.sortedByDescending { it.second.type.value }
            .joinToString(" and ") { "${it.second.type.label} on ${Sq.name(it.first)}" }
        return "Fork! The ${piece.type.label} attacks the $names at once — they can't save both."
    }

    private fun detectPinOrSkewer(after: Position, sq: Int, piece: Piece): List<Pair<Tag, String>> {
        if (!piece.type.isSlider) return emptyList()
        val them = piece.side.opposite
        val out = mutableListOf<Pair<Tag, String>>()
        for ((df, dr) in Position.sliderDirs(piece.type)) {
            var f = Sq.file(sq) + df
            var r = Sq.rank(sq) + dr
            var first: Pair<Int, Piece>? = null
            while (Sq.onBoard(f, r)) {
                val t = Sq.of(f, r)
                val q = after[t]
                if (q != null) {
                    if (first == null) {
                        if (q.side != them) break
                        first = t to q
                    } else {
                        if (q.side == them) {
                            val (fsq, fp) = first
                            when {
                                q.type == PieceType.KING && fp.type != PieceType.PAWN -> out += Tag.PIN to
                                    "Pins the ${fp.type.label} on ${Sq.name(fsq)} to the king — it cannot legally move."
                                fp.type == PieceType.KING -> out += Tag.SKEWER to
                                    "Skewer: the king must step aside, exposing the ${q.type.label} on ${Sq.name(t)}."
                                fp.type.value > q.type.value + 50 && q.type != PieceType.PAWN -> out += Tag.SKEWER to
                                    "Skewer: attacks the ${fp.type.label}; when it moves, the ${q.type.label} behind it falls."
                                q.type.value > fp.type.value + 50 && fp.type != PieceType.PAWN -> out += Tag.PIN to
                                    "Pins the ${fp.type.label} on ${Sq.name(fsq)} to the ${q.type.label} behind it."
                            }
                        }
                        break
                    }
                }
                f += df; r += dr
            }
        }
        return out
    }

    private fun detectDiscovered(before: Position, after: Position, move: Move): String? {
        val us = before.sideToMove
        val them = us.opposite
        for ((sq, p) in after.pieces()) {
            if (p.side != us || !p.type.isSlider || sq == move.to) continue
            val was = before.attacksFrom(sq).toSet()
            val now = after.attacksFrom(sq)
            for (t in now) {
                if (t in was) continue
                val q = after[t] ?: continue
                if (q.side != them) continue
                if (q.type == PieceType.KING) return "Discovered check: moving away unleashes your ${p.type.label} on the king."
                if (q.type.value > p.type.value || after.attackersOf(t, them).isEmpty() && q.type != PieceType.PAWN)
                    return "Discovered attack: moving this piece opens your ${p.type.label}'s line onto the ${q.type.label} on ${Sq.name(t)}."
            }
        }
        return null
    }

    data class Threat(val value: Int, val description: String)

    /** The opponent's most dangerous immediate threat in [pos] (opponent to move). */
    fun biggestThreat(pos: Position): Threat? {
        var best: Threat? = null
        for (m in pos.legalMoves()) {
            val after = pos.play(m, validate = false)
            if (after.isCheckmate) return Threat(100_000, "${pos.toSan(m)} would be checkmate")
            val victim = pos.capturedPiece(m) ?: continue
            val attacker = pos[m.from]!!
            val recapture = after.attackersOf(m.to, pos.sideToMove.opposite).isNotEmpty()
            val gain = if (recapture) victim.type.value - attacker.type.value else victim.type.value
            if (gain >= 150 && (best == null || gain > best.value)) {
                best = Threat(gain, "${pos.toSan(m)} would win your ${victim.type.label}")
            }
        }
        return best
    }

    /** Material change for [us] after playing up to 4 plies of the PV (even ply count to settle trades). */
    fun materialSwingAlongPv(pos: Position, pv: List<String>, us: Side): Int {
        var p = pos
        val start = pos.materialBalance()
        var settled = start
        for ((i, u) in pv.take(4).withIndex()) {
            val m = runCatching { Move.fromUci(u) }.getOrNull() ?: break
            if (!p.isLegal(m)) break
            p = p.play(m, validate = false)
            if (i % 2 == 1 || i == pv.lastIndex) settled = p.materialBalance()
        }
        val diff = settled - start
        return if (us == Side.WHITE) diff else -diff
    }

    // ---------------------------------------------------------------- positional

    private fun positionalReasons(before: Position, after: Position, move: Move, mover: Piece): List<Pair<Tag, String>> {
        val out = mutableListOf<Pair<Tag, String>>()
        val us = mover.side
        val backRank = if (us == Side.WHITE) 0 else 7
        val toName = Sq.name(move.to)
        val center = setOf("d4", "e4", "d5", "e5")
        val extCenter = setOf("c3", "d3", "e3", "f3", "c4", "f4", "c5", "f5", "c6", "d6", "e6", "f6")

        if ((mover.type == PieceType.KNIGHT || mover.type == PieceType.BISHOP) && Sq.rank(move.from) == backRank &&
            before.fullmoveNumber <= 15
        ) out += Tag.DEVELOPMENT to "Develops the ${mover.type.label} off the back rank, toward the action."

        if (toName in center) out += Tag.CENTER to "Takes control of the centre square $toName."
        else if (mover.type != PieceType.KING && after.attacksFrom(move.to).count { Sq.name(it) in center } >= 2)
            out += Tag.CENTER to "From $toName the ${mover.type.label} eyes key central squares."
        else if (mover.type == PieceType.PAWN && toName in extCenter && before.fullmoveNumber <= 12)
            out += Tag.CENTER to "Builds a pawn presence in the centre."

        if (mover.type == PieceType.ROOK || mover.type == PieceType.QUEEN) {
            val file = Sq.file(move.to)
            val pawns = after.pieces().filter { it.second.type == PieceType.PAWN && Sq.file(it.first) == file }
            if (Sq.file(move.from) != file) {
                when {
                    pawns.isEmpty() -> out += Tag.OPEN_FILE to "Puts the ${mover.type.label} on the open ${'a' + file}-file."
                    pawns.none { it.second.side == us } && mover.type == PieceType.ROOK ->
                        out += Tag.OPEN_FILE to "Puts the rook on the half-open ${'a' + file}-file, pressing on their pawn."
                }
            }
        }

        if (mover.type == PieceType.PAWN && isPassed(after, move.to, us))
            out += Tag.PASSED_PAWN to "Pushes a passed pawn — no enemy pawn can stop it on its way to promotion."

        if (mover.type == PieceType.KING && after.pieces().count { it.second.type == PieceType.QUEEN } == 0)
            out += Tag.POSITIONAL to "In the endgame the king is a fighting piece; this brings it closer to the action."

        if (out.isEmpty()) {
            val mobBefore = before.attacksFrom(move.from).size
            val mobAfter = after.attacksFrom(move.to).size
            if (mover.type != PieceType.PAWN && mobAfter > mobBefore + 1)
                out += Tag.POSITIONAL to "Improves the ${mover.type.label}: it controls $mobAfter squares instead of $mobBefore."
        }
        return out
    }

    private fun isPassed(pos: Position, sq: Int, us: Side): Boolean {
        val f = Sq.file(sq)
        val r = Sq.rank(sq)
        return pos.pieces().none { (s, p) ->
            p.type == PieceType.PAWN && p.side != us && kotlin.math.abs(Sq.file(s) - f) <= 1 &&
                if (us == Side.WHITE) Sq.rank(s) > r else Sq.rank(s) < r
        }
    }

    // ---------------------------------------------------------------- wording

    private fun shortLine(pos: Position, line: List<String>, plies: Int): String {
        var no = pos.fullmoveNumber
        var white = pos.sideToMove == Side.WHITE
        val sb = StringBuilder()
        for ((i, s) in line.take(plies).withIndex()) {
            if (white) sb.append("$no.") else if (i == 0) sb.append("$no...")
            sb.append(s).append(' ')
            if (!white) no++
            white = !white
        }
        return sb.toString().trim()
    }

    fun materialWords(cp: Int): String = when {
        cp >= 850 -> "a queen"
        cp >= 450 -> "a rook"
        cp >= 280 -> "a minor piece"
        cp >= 180 -> "two pawns"
        else -> "a pawn"
    } + " (${String.format(java.util.Locale.US, "%.1f", cp / 100.0)} pawns)"

    fun deltaWords(cp: Int): String = if (cp >= 2000) "a forced mate" else
        "${String.format(java.util.Locale.US, "%.1f", cp / 100.0)} pawns"
}
