package com.mrchess.core.rules

object Pgn {
    private val TAG = Regex("""^\s*\[(\w+)\s+"((?:[^"\\]|\\.)*)"\s*]\s*$""")
    private val RESULTS = setOf("1-0", "0-1", "1/2-1/2", "*")

    /** Parses every game in a PGN string. Comments, NAGs and variations are skipped. */
    fun parseAll(pgn: String): List<Game> {
        val games = mutableListOf<Game>()
        val tags = linkedMapOf<String, String>()
        val body = StringBuilder()
        fun flush() {
            if (tags.isEmpty() && body.isBlank()) return
            games += build(LinkedHashMap(tags), body.toString())
            tags.clear(); body.clear()
        }
        for (line in pgn.lines()) {
            val m = TAG.matchEntire(line)
            if (m != null) {
                if (body.isNotBlank()) flush()
                tags[m.groupValues[1]] = m.groupValues[2].replace("\\\"", "\"")
            } else if (!line.trimStart().startsWith("%")) {
                body.append(line).append('\n')
            }
        }
        flush()
        return games
    }

    fun parse(pgn: String): Game = parseAll(pgn).firstOrNull() ?: throw IllegalArgumentException("No game found in PGN")

    private fun build(tags: MutableMap<String, String>, movetext: String): Game {
        val startFen = tags["FEN"]?.takeIf { tags["SetUp"] != "0" } ?: Position.START_FEN
        val game = Game(startFen, emptyList(), tags)
        for (tok in tokenize(movetext)) {
            if (tok in RESULTS) { tags["Result"] = tok; break }
            game.playSan(tok)
        }
        return game
    }

    private fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var i = 0
        val sb = StringBuilder()
        fun emit() {
            if (sb.isNotEmpty()) {
                val t = sb.toString().replace(Regex("^\\d+\\.+"), "")
                if (t.isNotEmpty() && !t.startsWith("$") && !t.all { it == '.' } && t.toIntOrNull() == null) out += t
                sb.clear()
            }
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '{' -> { emit(); i = text.indexOf('}', i).let { if (it < 0) text.length else it } }
                c == ';' -> { emit(); i = text.indexOf('\n', i).let { if (it < 0) text.length else it } }
                c == '(' -> { emit(); depth++ }
                c == ')' -> { emit(); depth-- }
                depth > 0 -> {}
                c.isWhitespace() -> emit()
                else -> sb.append(c)
            }
            i++
        }
        emit()
        return out
    }

    fun write(game: Game): String = buildString {
        val tags = LinkedHashMap<String, String>()
        for (k in listOf("Event", "Site", "Date", "Round", "White", "Black")) tags[k] = game.tags[k] ?: "?"
        tags["Result"] = game.result.pgn
        if (game.startFen != Position.START_FEN) { tags["SetUp"] = "1"; tags["FEN"] = game.startFen }
        game.tags.forEach { (k, v) -> if (k !in tags) tags[k] = v }
        tags.forEach { (k, v) -> append("[$k \"${v.replace("\"", "\\\"")}\"]\n") }
        append('\n')
        append(movetext(game))
        append(' ').append(game.result.pgn).append('\n')
    }

    fun movetext(game: Game): String {
        val start = game.positions.first()
        var moveNo = start.fullmoveNumber
        var white = start.sideToMove == Side.WHITE
        val parts = mutableListOf<String>()
        for ((i, san) in game.sanMoves.withIndex()) {
            if (white) parts += "$moveNo. $san"
            else { parts += if (i == 0) "$moveNo... $san" else san; moveNo++ }
            white = !white
        }
        return parts.joinToString(" ")
    }
}
