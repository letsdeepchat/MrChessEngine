package com.mrchess.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mrchess.app.ui.BoardTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** User preferences persisted with Jetpack Preferences DataStore. */
class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("board_theme")
    private val soundKey = booleanPreferencesKey("board_sounds")

    val boardTheme: Flow<BoardTheme> = context.settingsStore.data.map { BoardTheme.byId(it[themeKey]) }
    val soundOn: Flow<Boolean> = context.settingsStore.data.map { it[soundKey] ?: true }

    suspend fun setBoardTheme(t: BoardTheme) = context.settingsStore.edit { it[themeKey] = t.id }
    suspend fun setSoundOn(on: Boolean) = context.settingsStore.edit { it[soundKey] = on }
}
