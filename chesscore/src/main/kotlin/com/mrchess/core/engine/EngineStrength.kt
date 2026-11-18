package com.mrchess.core.engine

/**
 * Engine playing strength.
 *
 * Stockfish's UCI_Elo is calibrated (CCRL-like scale, 60s+0.6s) between 1320 and 3190.
 * Below that we use "Skill Level" 0..20 without the Elo limiter; above it we switch the
 * limiter off entirely, which on a modern phone plays well beyond 3200.
 */
data class EngineStrength(
    val label: String,
    val limitElo: Int? = null,
    val skillLevel: Int = 20,
    /** Think time per move when playing against the user. */
    val moveTimeMs: Long = 1000,
) {
    val isFull: Boolean get() = limitElo == null && skillLevel >= 20

    companion object {
        const val MIN_ELO = 1320
        const val MAX_LIMITED_ELO = 3190

        val BEGINNER = EngineStrength("Beginner (~800)", skillLevel = 0, moveTimeMs = 150)
        val CASUAL = EngineStrength("Casual (~1100)", skillLevel = 3, moveTimeMs = 250)
        val FULL = EngineStrength("Full strength (3200+)", limitElo = null, skillLevel = 20, moveTimeMs = 1000)

        /**
         * Think time per move for a bot of [elo]. Stockfish's weakening comes from its skill
         * setting, not from the clock, so weaker bots answer almost at once and only the strong
         * ones take up to a second. Full strength on a phone is still far above 3200 at 1 s.
         */
        fun moveTimeFor(elo: Int): Long = when {
            elo < 950 -> BEGINNER.moveTimeMs
            elo < MIN_ELO -> CASUAL.moveTimeMs
            elo < 1600 -> 300
            elo < 2000 -> 450
            elo < 2400 -> 600
            elo < 2800 -> 800
            else -> 1000
        }

        /** Maps a slider value to a strength. 3200 and above means "unlimited". */
        fun fromElo(elo: Int, moveTimeMs: Long = moveTimeFor(elo)): EngineStrength = when {
            elo < 950 -> BEGINNER
            elo < MIN_ELO -> CASUAL
            elo >= 3200 -> FULL.copy(moveTimeMs = moveTimeMs)
            else -> EngineStrength("Elo $elo", limitElo = elo.coerceIn(MIN_ELO, MAX_LIMITED_ELO), moveTimeMs = moveTimeMs)
        }

        val PRESETS = listOf(800, 1100, 1400, 1700, 2000, 2300, 2600, 2900, 3200).map { fromElo(it) }
    }
}
