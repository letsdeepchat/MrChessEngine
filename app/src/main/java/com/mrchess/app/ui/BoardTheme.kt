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
    TOURNAMENT_BLUE("tournament_blue", "Tournament Blue", Color(0xFFDEE3E6), Color(0xFF8CA2AD), Color(0x8899CCEE), Color(0x9966AACC)),
    WALNUT_WOOD("walnut_wood", "Walnut Wood", Color(0xFFF0D9B5), Color(0xFFB58863), Color(0x88E8D23C), Color(0x99C8B040)),
    MIDNIGHT_SLATE("midnight_slate", "Midnight Slate", Color(0xFFC8CCD4), Color(0xFF5C677D), Color(0x8870B8FF), Color(0x994A90E2)),
    ROYAL_VIOLET("royal_violet", "Royal Violet", Color(0xFFE8E2F4), Color(0xFF7B61FF), Color(0x88FFD54F), Color(0x99B39DFF));

    companion object {
        val DEFAULT = CLASSIC_GREEN
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** The theme the whole app's boards use; provided at the top of the activity. */
val LocalBoardTheme = staticCompositionLocalOf { BoardTheme.DEFAULT }
