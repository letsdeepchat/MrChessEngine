package com.mrchess.core.speech

/**
 * Turns chess text into something a text-to-speech engine reads naturally:
 * "Bxf7+" → "Bishop takes f7, check", "O-O" → "Castles kingside", "+0.45" → "plus zero point four five".
 */
object SpeechFormatter {
    private val PIECES = mapOf('K' to "King", 'Q' to "Queen", 'R' to "Rook", 'B' to "Bishop", 'N' to "Knight")
    private val SAN = Regex("""^([KQRBN])?([a-h])?([1-8])?(x)?([a-h][1-8])(?:=?([QRBN]))?([+#])?$""")
    private val DIGITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val TEENS = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** Speaks one SAN move, or returns null if [token] is not SAN. */
    fun san(token: String): String? {
        val t = token.trim().trimEnd('!', '?')
        val suffix = when {
            t.endsWith("#") -> ", checkmate"
            t.endsWith("+") -> ", check"
            else -> ""
        }
        val core = t.trimEnd('+', '#')
        if (core == "O-O" || core == "0-0") return "Castles kingside$suffix"
        if (core == "O-O-O" || core == "0-0-0") return "Castles queenside$suffix"
        val m = SAN.matchEntire(core) ?: return null
        val (piece, fromFile, fromRank, capture, to, promo) = m.destructured
        val sb = StringBuilder()
        if (piece.isNotEmpty()) {
            sb.append(PIECES.getValue(piece[0]))
            if (fromFile.isNotEmpty() || fromRank.isNotEmpty()) sb.append(" from ").append(fromFile).append(fromRank)
        } else {
            sb.append(if (capture.isNotEmpty()) "$fromFile pawn" else "Pawn")
        }
        sb.append(if (capture.isNotEmpty()) " takes " else " to ").append(to)
        if (promo.isNotEmpty()) sb.append(", promotes to ").append(PIECES.getValue(promo[0]).lowercase())
        return sb.append(suffix).toString()
    }

    /** Integer to words (0..999), enough for move numbers and evaluations. */
    fun number(n: Int): String = when {
        n < 0 -> "minus " + number(-n)
        n < 10 -> DIGITS[n]
        n < 20 -> TEENS[n - 10]
        n < 100 -> TENS[n / 10] + if (n % 10 != 0) " " + DIGITS[n % 10] else ""
        n < 1000 -> DIGITS[n / 100] + " hundred" + if (n % 100 != 0) " " + number(n % 100) else ""
        else -> n.toString()
    }

    /** "+0.45" → "plus zero point four five"; "-1.2" → "minus one point two"; "M3" → "mate in three". */
    fun eval(token: String): String? {
        Regex("""^(-)?M(\d+)$""").matchEntire(token)?.let { m ->
            val n = number(m.groupValues[2].toInt())
            return if (m.groupValues[1].isEmpty()) "mate in $n" else "getting mated in $n"
        }
        val m = Regex("""^([+-])?(\d+)(?:\.(\d+))?$""").matchEntire(token) ?: return null
        val sign = when (m.groupValues[1]) { "+" -> "plus "; "-" -> "minus "; else -> "" }
        val whole = number(m.groupValues[2].toIntOrNull() ?: return null)
        val frac = m.groupValues[3].map { DIGITS[it - '0'] }.joinToString(" ")
        return sign + whole + if (frac.isNotEmpty()) " point $frac" else ""
    }

    /** Speaks a SAN line such as ["Nc3", "Nf6", "Bg5"]. */
    fun line(sans: List<String>): String = sans.mapNotNull { san(it) ?: it }.joinToString(", ")

    /**
     * Converts free text containing SAN moves and evaluations. Bare squares like "e4" are left as
     * they are (they usually mean a square in prose); move numbers like "3." or "3..." are dropped.
     */
    fun text(s: String): String = s.split(' ').joinToString(" ") { word ->
        val lead = word.takeWhile { it == '(' || it == '"' }
        val trail = word.takeLastWhile { it in ",.;:)\"" && it != '+' }
        var core = word.removePrefix(lead).removeSuffix(trail)
        // "3.Nc3" / "3...Nf6" → "Nc3"
        core = core.replace(Regex("""^\d+\.+"""), "")
        if (core.isEmpty()) return@joinToString lead + trail.trimStart('.')
        val spoken = when {
            core.length >= 3 && (core[0] in PIECES || core.contains('x') || core.endsWith('+') || core.endsWith('#') || core.contains('=') || core.startsWith("O-O")) -> san(core)
            else -> eval(core)?.takeIf { core.startsWith('+') || core.startsWith('-') || core.startsWith('M') || core.contains('.') }
        }
        lead + (spoken ?: core) + trail
    }
}
