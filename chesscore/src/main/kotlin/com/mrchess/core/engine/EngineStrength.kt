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

        val BEGINNER = EngineStrength("Beginner (~800)", skillLevel = 0, moveTimeMs = 200)
        val CASUAL = EngineStrength("Casual (~1100)", skillLevel = 3, moveTimeMs = 300)
        val FULL = EngineStrength("Full strength (3200+)", limitElo = null, skillLevel = 20, moveTimeMs = 2000)

        /** Maps a slider value to a strength. 3200 and above means "unlimited". */
        fun fromElo(elo: Int, moveTimeMs: Long = 1000): EngineStrength = when {
            elo < 950 -> BEGINNER
            elo < MIN_ELO -> CASUAL
            elo >= 3200 -> FULL.copy(moveTimeMs = maxOf(moveTimeMs, 1500))
            else -> EngineStrength("Elo $elo", limitElo = elo.coerceIn(MIN_ELO, MAX_LIMITED_ELO), moveTimeMs = moveTimeMs)
        }

        val PRESETS = listOf(800, 1100, 1400, 1700, 2000, 2300, 2600, 2900, 3200).map { fromElo(it) }
    }
}
