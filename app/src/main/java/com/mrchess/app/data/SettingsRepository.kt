package com.mrchess.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mrchess.app.audio.SoundPack
import com.mrchess.app.ui.BoardTheme
import com.mrchess.app.ui.BotDefaults
import com.mrchess.app.ui.SideChoice
import com.mrchess.core.puzzle.PuzzleRating
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Puzzle progress: Elo estimate, counts and the ids already played. */
data class PuzzleStats(val rating: Double = PuzzleRating.START, val played: Int = 0, val solved: Int = 0, val seen: Set<String> = emptySet())

/** User preferences persisted with Jetpack Preferences DataStore. */
class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("board_theme")
    private val legacySoundKey = booleanPreferencesKey("board_sounds")
    private val soundPackKey = stringPreferencesKey("sound_pack")
    private val puzzleRatingKey = doublePreferencesKey("puzzle_rating")
    private val puzzlePlayedKey = intPreferencesKey("puzzle_played")
    private val puzzleSolvedKey = intPreferencesKey("puzzle_solved")
    private val puzzleSeenKey = stringSetPreferencesKey("puzzle_seen")
    private val botEloKey = intPreferencesKey("default_bot_elo")
    private val botColorKey = stringPreferencesKey("default_bot_color")
    private val guestKey = booleanPreferencesKey("is_guest_mode")

    /** Stockfish New Game defaults ("Set as default Elo level and colour"). */
    val botDefaults: Flow<BotDefaults> = context.settingsStore.data.map {
        BotDefaults(it[botEloKey] ?: 1500, SideChoice.byId(it[botColorKey]), saved = it[botEloKey] != null)
    }
    suspend fun setBotDefaults(elo: Int, color: SideChoice) = context.settingsStore.edit {
        it[botEloKey] = elo
        it[botColorKey] = color.id
    }

    /** True once the player chose "Play as Guest" on the welcome screen. */
    val guestMode: Flow<Boolean> = context.settingsStore.data.map { it[guestKey] ?: false }
    suspend fun setGuestMode(on: Boolean) = context.settingsStore.edit { it[guestKey] = on }

    val boardTheme: Flow<BoardTheme> = context.settingsStore.data.map { BoardTheme.byId(it[themeKey]) }

    /** Older versions only had an on/off switch; "off" becomes the Muted pack. */
    val soundPack: Flow<SoundPack> = context.settingsStore.data.map {
        it[soundPackKey]?.let(SoundPack::byId) ?: if (it[legacySoundKey] == false) SoundPack.MUTED else SoundPack.DEFAULT
    }

    val puzzleStats: Flow<PuzzleStats> = context.settingsStore.data.map {
        PuzzleStats(it[puzzleRatingKey] ?: PuzzleRating.START, it[puzzlePlayedKey] ?: 0, it[puzzleSolvedKey] ?: 0, it[puzzleSeenKey] ?: emptySet())
    }

    suspend fun setBoardTheme(t: BoardTheme) = context.settingsStore.edit { it[themeKey] = t.id }
    suspend fun setSoundPack(p: SoundPack) = context.settingsStore.edit { it[soundPackKey] = p.id }

    suspend fun recordPuzzle(id: String, newRating: Double?, solved: Boolean) = context.settingsStore.edit {
        it[puzzleSeenKey] = (it[puzzleSeenKey] ?: emptySet()) + id
        if (newRating != null) {
            it[puzzleRatingKey] = newRating
            it[puzzlePlayedKey] = (it[puzzlePlayedKey] ?: 0) + 1
            if (solved) it[puzzleSolvedKey] = (it[puzzleSolvedKey] ?: 0) + 1
        }
    }

    suspend fun resetPuzzles() = context.settingsStore.edit {
        it.remove(puzzleRatingKey); it.remove(puzzlePlayedKey); it.remove(puzzleSolvedKey); it.remove(puzzleSeenKey)
    }
}
