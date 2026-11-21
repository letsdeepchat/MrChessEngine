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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.mrchess.core.online.Elo
import com.mrchess.core.puzzle.PuzzleHistory
import kotlin.math.roundToInt

private val Context.settingsStore by preferencesDataStore(name = "settings")


/**
 * User preferences. Look-and-feel settings live in Jetpack Preferences DataStore; values the
 * first frame depends on (default bot Elo/colour) and progress that must never be lost (puzzle
 * history, username, online rating) live in SharedPreferences written with commit(), so they
 * are on disk before the call returns and readable synchronously at start-up.
 */
class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("mrchess_prefs", Context.MODE_PRIVATE)
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

    private val _botDefaults = MutableStateFlow(readBotDefaults())
    /** Stockfish New Game defaults ("Set as default Elo level and colour"). */
    val botDefaults: StateFlow<BotDefaults> = _botDefaults

    /** Synchronous read for cold start: the Play tab's first frame already uses the saved Elo. */
    fun readBotDefaults(): BotDefaults =
        if (!prefs.contains(KEY_BOT_ELO)) BotDefaults()
        else BotDefaults(prefs.getInt(KEY_BOT_ELO, 1500), SideChoice.byPrefValue(prefs.getString(KEY_BOT_COLOR, null)), saved = true)

    fun setBotDefaults(elo: Int, color: SideChoice) {
        prefs.edit().putInt(KEY_BOT_ELO, elo).putString(KEY_BOT_COLOR, color.prefValue).commit()
        _botDefaults.value = BotDefaults(elo, color, saved = true)
    }

    /** One-time copy of defaults saved by 1.6 and earlier (DataStore) into SharedPreferences. */
    suspend fun migrateBotDefaults(): BotDefaults? {
        if (prefs.contains(KEY_BOT_ELO)) return null
        val d = context.settingsStore.data.first()
        val elo = d[botEloKey] ?: return null
        setBotDefaults(elo, SideChoice.byId(d[botColorKey]))
        return _botDefaults.value
    }

    // ------------------------------------------------------------------ puzzles

    private val _puzzleHistory = MutableStateFlow(readPuzzleHistory())
    val puzzleHistory: StateFlow<PuzzleHistory> = _puzzleHistory

    private fun readPuzzleHistory(): PuzzleHistory = PuzzleHistory(
        elo = prefs.getInt(KEY_PUZZLE_ELO, PuzzleRating.START.toInt()),
        attempts = prefs.getInt(KEY_PUZZLE_ATTEMPTS, 0),
        solvedCount = prefs.getInt(KEY_PUZZLE_SOLVED_COUNT, 0),
        solved = prefs.getStringSet(KEY_SOLVED, emptySet())!!.toSet(),
        failed = decodeFailed(prefs.getString(KEY_FAILED, null)),
    )

    fun savePuzzleHistory(h: PuzzleHistory) {
        prefs.edit()
            .putInt(KEY_PUZZLE_ELO, h.elo).putInt(KEY_PUZZLE_ATTEMPTS, h.attempts).putInt(KEY_PUZZLE_SOLVED_COUNT, h.solvedCount)
            .putStringSet(KEY_SOLVED, HashSet(h.solved)).putString(KEY_FAILED, encodeFailed(h.failed))
            .commit()
        _puzzleHistory.value = h
    }

    /** One-time copy of the 1.6 DataStore puzzle stats (ids played then count as solved). */
    suspend fun migratePuzzles() {
        if (prefs.contains(KEY_PUZZLE_ELO)) return
        val d = context.settingsStore.data.first()
        val rating = d[puzzleRatingKey] ?: return
        savePuzzleHistory(PuzzleHistory(rating.roundToInt(), d[puzzlePlayedKey] ?: 0, d[puzzleSolvedKey] ?: 0, d[puzzleSeenKey] ?: emptySet()))
    }

    fun resetPuzzleHistory() = savePuzzleHistory(PuzzleHistory())

    // ------------------------------------------------------------------ profile & online

    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME, null))
    /** The player's chosen @username (null: not chosen yet, a default is derived from the account). */
    val username: StateFlow<String?> = _username
    fun setUsername(name: String) { prefs.edit().putString(KEY_USERNAME, name).commit(); _username.value = name }

    private val _onlineElo = MutableStateFlow(prefs.getInt(KEY_ONLINE_ELO, Elo.ONLINE_START))
    val onlineElo: StateFlow<Int> = _onlineElo
    val onlineGames: Int get() = prefs.getInt(KEY_ONLINE_GAMES, 0)
    fun setOnlineElo(elo: Int, games: Int = onlineGames) {
        prefs.edit().putInt(KEY_ONLINE_ELO, elo).putInt(KEY_ONLINE_GAMES, games).commit()
        _onlineElo.value = elo
    }

    /** Applies a finished online game's rating change once; returns false if it was already counted. */
    fun markOnlineGameRated(gameId: String): Boolean {
        val done = prefs.getStringSet(KEY_RATED_GAMES, emptySet())!!
        if (gameId in done) return false
        prefs.edit().putStringSet(KEY_RATED_GAMES, (done.toList().takeLast(50) + gameId).toHashSet()).commit()
        return true
    }

    var onlineTimeControl: String?
        get() = prefs.getString(KEY_ONLINE_TC, null)
        set(v) { prefs.edit().putString(KEY_ONLINE_TC, v).commit() }
    var onlineFilter: String?
        get() = prefs.getString(KEY_ONLINE_FILTER, null)
        set(v) { prefs.edit().putString(KEY_ONLINE_FILTER, v).commit() }

    /** True once the player chose "Play as Guest" on the welcome screen. */
    val guestMode: Flow<Boolean> = context.settingsStore.data.map { it[guestKey] ?: false }
    suspend fun setGuestMode(on: Boolean) = context.settingsStore.edit { it[guestKey] = on }

    val boardTheme: Flow<BoardTheme> = context.settingsStore.data.map { BoardTheme.byId(it[themeKey]) }

    /** Older versions only had an on/off switch; "off" becomes the Muted pack. */
    val soundPack: Flow<SoundPack> = context.settingsStore.data.map {
        it[soundPackKey]?.let(SoundPack::byId) ?: if (it[legacySoundKey] == false) SoundPack.MUTED else SoundPack.DEFAULT
    }

    suspend fun setBoardTheme(t: BoardTheme) = context.settingsStore.edit { it[themeKey] = t.id }
    suspend fun setSoundPack(p: SoundPack) = context.settingsStore.edit { it[soundPackKey] = p.id }

    companion object {
        private const val KEY_BOT_ELO = "default_bot_elo"
        private const val KEY_BOT_COLOR = "default_bot_color"
        private const val KEY_PUZZLE_ELO = "user_puzzle_elo"
        private const val KEY_PUZZLE_ATTEMPTS = "puzzle_attempts"
        private const val KEY_PUZZLE_SOLVED_COUNT = "puzzle_solved_count"
        private const val KEY_SOLVED = "solved_puzzle_ids"
        private const val KEY_FAILED = "failed_puzzle_history"
        private const val KEY_USERNAME = "username"
        private const val KEY_ONLINE_ELO = "online_elo"
        private const val KEY_ONLINE_GAMES = "online_games_played"
        private const val KEY_RATED_GAMES = "online_rated_game_ids"
        private const val KEY_ONLINE_TC = "online_time_control"
        private const val KEY_ONLINE_FILTER = "online_rating_filter"

        /** "id:attempt,id:attempt" */
        fun encodeFailed(m: Map<String, Int>) = m.entries.joinToString(",") { "${it.key}:${it.value}" }
        fun decodeFailed(s: String?): Map<String, Int> = s.orEmpty().split(',').mapNotNull { e ->
            val i = e.lastIndexOf(':'); if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
    }
}
