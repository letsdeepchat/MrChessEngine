package com.mrchess.core.review

import com.mrchess.core.rules.Position

/**
 * Built-in opening book: every position reached in the first [MAX_PLY] plies of ~3,800 named
 * ECO lines (lichess-org/chess-openings, CC0). Lookups are by position, so transpositions count.
 * Loaded lazily on first use (a few hundred milliseconds on a phone).
 */
object OpeningBook {
    const val MAX_PLY = 16

    /** [exact]: a named line ends at this position. Otherwise the name of the shortest line through it. */
    data class Opening(val eco: String, val name: String, val exact: Boolean, val plies: Int)

    private val startKey = key(Position.fromFen(Position.START_FEN))
    private val byKey: Map<String, Opening> by lazy { load() }

    /** Board, side to move, castling and en passant: the FEN without the move counters. */
    fun key(p: Position): String = p.toFen().split(' ').take(4).joinToString(" ")

    fun isStart(p: Position) = key(p) == startKey

    /** The named opening for this position (exact line name if one ends here), or null if out of book. */
    fun lookup(p: Position): Opening? = byKey[key(p)]

    fun inBook(p: Position) = isStart(p) || lookup(p) != null

    val size: Int get() = byKey.size

    private fun load(): Map<String, Opening> {
        val stream = OpeningBook::class.java.getResourceAsStream("/com/mrchess/core/review/eco.tsv") ?: return emptyMap()
        // Pass 1: the position keys along every line (first MAX_PLY plies).
        class Line(val eco: String, val name: String, val keys: List<String>, val complete: Boolean)
        val lines = ArrayList<Line>(4096)
        val prefixes = HashMap<String, Position>(16_384)
        stream.bufferedReader().useLines { rows ->
            rows.drop(1).forEach { row ->
                val parts = row.split('\t')
                if (parts.size < 3) return@forEach
                val sans = parts[2].split(' ').filter { it.isNotBlank() && !it.first().isDigit() }
                var pos = Position.fromFen(Position.START_FEN)
                val prefix = StringBuilder()
                val keys = ArrayList<String>(MAX_PLY)
                for (san in sans.take(MAX_PLY)) {
                    prefix.append(san).append(' ')
                    val cur = pos
                    pos = prefixes.getOrPut(prefix.toString()) {
                        runCatching { cur.play(cur.parseSan(san)) }.getOrNull() ?: return@forEach
                    }
                    keys += key(pos)
                }
                lines += Line(parts[0], parts[1], keys, sans.size <= MAX_PLY)
            }
        }
        // Pass 2: positions where a named line ends keep that name.
        val map = HashMap<String, Opening>(16_384)
        for (l in lines) if (l.complete && l.keys.isNotEmpty()) map.putIfAbsent(l.keys.last(), Opening(l.eco, l.name, true, l.keys.size))
        // Pass 3: other book positions inherit the deepest named position before them on their line,
        // so 1. e4 e5 2. Bc4 Nc6 reads "Bishop's Opening" rather than some long line passing through.
        for (l in lines) {
            var named: Opening? = null
            for (k in l.keys) {
                val exact = map[k]?.takeIf { it.exact }
                if (exact != null) { named = exact; continue }
                if (k !in map) map[k] = (named ?: Opening(l.eco, l.name, true, l.keys.size)).copy(exact = false)
            }
        }
        return map
    }
}
