package com.mrchess.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Board colour palettes. [id] is what gets persisted, so never rename it. */
enum class BoardTheme(
    val id: String,
    val label: String,
    val light: Color,
    val dark: Color,
    val lastMove: Color,
    val selected: Color,
) {
    CLASSIC_GREEN("classic_green", "Classic Green", Color(0xFFEBECD0), Color(0xFF739552), Color(0x88F6F669), Color(0x99BACA44)),
    BROWN_WOOD("walnut_wood", "Brown Wood", Color(0xFFF0D9B5), Color(0xFFB58863), Color(0x88E8D23C), Color(0x99C8B040)),
    ICE_BLUE("tournament_blue", "Ice Blue", Color(0xFFE4EEF4), Color(0xFF7FA3BD), Color(0x8899D6FF), Color(0x995BB4E5)),
    DARK_PURPLE("royal_violet", "Dark Purple", Color(0xFFD8CDEB), Color(0xFF5B4392), Color(0x88FFD54F), Color(0x99B39DFF)),
    TOURNAMENT_SLATE("midnight_slate", "Tournament Slate", Color(0xFFD3D7DE), Color(0xFF66738A), Color(0x8870B8FF), Color(0x994A90E2));

    companion object {
        val DEFAULT = CLASSIC_GREEN
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** The theme the whole app's boards use; provided at the top of the activity. */
val LocalBoardTheme = staticCompositionLocalOf { BoardTheme.DEFAULT }
