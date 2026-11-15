package com.mrchess.app.data

import android.content.Context
import com.mrchess.core.rules.Game
import com.mrchess.core.rules.Pgn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedGame(val file: File, val title: String, val result: String, val date: String)

/** Stores the user's finished games as PGN files in app-private storage. */
class GameStore(context: Context) {
    private val dir = File(context.filesDir, "games").apply { mkdirs() }

    /** Saves [game]; pass the file from an earlier save of the same game to overwrite it. */
    fun save(game: Game, existing: File? = null): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        game.tags.putIfAbsent("Date", SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()))
        game.tags.putIfAbsent("Event", "MrChess training game")
        game.tags.putIfAbsent("Site", "Offline")
        val f = existing ?: File(dir, "game_$stamp.pgn")
        f.writeText(game.toPgn())
        return f
    }

    fun list(): List<SavedGame> = dir.listFiles { f -> f.extension == "pgn" }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f ->
            runCatching {
                val g = Pgn.parse(f.readText())
                SavedGame(
                    f,
                    "${g.tags["White"] ?: "?"} vs ${g.tags["Black"] ?: "?"}",
                    g.result.pgn,
                    g.tags["Date"] ?: "",
                )
            }.getOrNull()
        }

    fun load(f: File): Game = Pgn.parse(f.readText())

    fun delete(f: File) { f.delete() }
}
