package com.mrchess.core.online

import com.mrchess.core.rules.Side
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.pow
import kotlin.math.roundToInt

/** Online time controls. [id] is stored in Firestore, so it must never change. */
enum class TimeControl(val id: String, val label: String, val initialMs: Long, val incrementMs: Long) {
    BULLET_1("1+0", "1 min (Bullet)", 60_000, 0),
    BLITZ_3("3+0", "3 min (Blitz)", 180_000, 0),
    BLITZ_3_2("3+2", "3|2 (Blitz)", 180_000, 2_000),
    RAPID_10("10+0", "10 min (Rapid)", 600_000, 0);

    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: BLITZ_3 }
}

/** How far from your own rating an opponent may be. [delta] null = any rating. */
enum class RatingFilter(val id: String, val label: String, val delta: Int?) {
    PM_100("100", "±100", 100), PM_200("200", "±200", 200), PM_400("400", "±400", 400), ANY("any", "Any rating", null);

    fun range(rating: Int): IntRange = if (delta == null) 0..9999 else (rating - delta)..(rating + delta)

    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: PM_200 }
}

/** A player waiting in `matchmaking_queue/{uid}`. */
data class MatchTicket(
    val uid: String,
    val username: String,
    val rating: Int,
    val timeControl: String,
    val minRating: Int,
    val maxRating: Int,
    val status: String = STATUS_WAITING,
    val gameId: String? = null,
    /** Client clock (ms) of the last heartbeat; stale tickets are ignored. */
    val heartbeatMs: Long = 0,
) {
    companion object {
        const val STATUS_WAITING = "waiting"
        const val STATUS_MATCHED = "matched"
    }
}

object Matchmaking {
    /** A waiting ticket older than this (no heartbeat) belongs to a player who left. */
    const val STALE_MS = 45_000L
    const val HEARTBEAT_MS = 10_000L

    /** Both players accept each other: same time control, and each rating inside the other's filter. */
    fun compatible(me: MatchTicket, other: MatchTicket, nowMs: Long): Boolean =
        other.uid != me.uid &&
            other.status == MatchTicket.STATUS_WAITING &&
            other.timeControl == me.timeControl &&
            other.rating in me.minRating..me.maxRating &&
            me.rating in other.minRating..other.maxRating &&
            nowMs - other.heartbeatMs <= STALE_MS

    /** The best waiting opponent: closest rating first, then whoever has waited longest. */
    fun bestOpponent(me: MatchTicket, waiting: List<MatchTicket>, nowMs: Long): MatchTicket? =
        waiting.filter { compatible(me, it, nowMs) }.minWithOrNull(compareBy<MatchTicket> { kotlin.math.abs(it.rating - me.rating) }.thenBy { it.heartbeatMs })
}

/** Standard Elo. */
object Elo {
    const val ONLINE_START = 1000
    const val K = 32

    fun expected(rating: Int, opponent: Int): Double = 1.0 / (1.0 + 10.0.pow((opponent - rating) / 400.0))

    /** New rating after scoring [score] (1 win, 0.5 draw, 0 loss) against [opponent]. */
    fun update(rating: Int, opponent: Int, score: Double, k: Int = K): Int =
        (rating + k * (score - expected(rating, opponent))).roundToInt().coerceIn(100, 3500)
}

/** One live game as stored in `online_games/{id}`. */
data class OnlineGame(
    val id: String,
    val whiteUid: String,
    val blackUid: String,
    val whiteName: String,
    val blackName: String,
    val whiteRating: Int,
    val blackRating: Int,
    val timeControl: String,
    val startFen: String,
    val moves: List<String>,
    /** Remaining time of each side when the last move was made. */
    val whiteMs: Long,
    val blackMs: Long,
    val status: String,
    /** "1-0", "0-1", "1/2-1/2" once finished. */
    val result: String? = null,
    val termination: String? = null,
    /** uid of the player offering a draw, if any. */
    val drawOfferBy: String? = null,
) {
    val finished: Boolean get() = status == STATUS_FINISHED
    val sideToMove: Side get() = if ((moves.size + if (startFen.split(' ').getOrNull(1) == "b") 1 else 0) % 2 == 0) Side.WHITE else Side.BLACK
    fun sideOf(uid: String): Side? = when (uid) { whiteUid -> Side.WHITE; blackUid -> Side.BLACK; else -> null }
    fun uidOf(side: Side) = if (side == Side.WHITE) whiteUid else blackUid
    fun nameOf(side: Side) = if (side == Side.WHITE) whiteName else blackName
    fun ratingOf(side: Side) = if (side == Side.WHITE) whiteRating else blackRating
    fun msOf(side: Side) = if (side == Side.WHITE) whiteMs else blackMs

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_FINISHED = "finished"
    }
}

/** What the searching player sees. */
sealed interface MatchEvent {
    data class Searching(val range: IntRange) : MatchEvent
    data class Matched(val gameId: String) : MatchEvent
    data class Failed(val message: String) : MatchEvent
}

/** Who is playing online: Firebase uid plus the public profile fields. */
data class OnlineIdentity(val uid: String, val username: String, val rating: Int)

/**
 * Live online play (Firebase Firestore in the :cloudsync module). The app uses [Disabled] when the
 * build has no Firebase config.
 */
interface OnlinePlay {
    val available: Boolean

    /** Signs in (anonymously for guests) and returns the uid. */
    suspend fun ensureSignedIn(): Result<String>

    /** Queue for a game; emits [MatchEvent.Matched] once paired. Cancelling the flow leaves the queue. */
    fun findMatch(me: OnlineIdentity, tc: TimeControl, filter: RatingFilter): Flow<MatchEvent>

    fun observeGame(gameId: String): Flow<OnlineGame>

    /**
     * Appends [uci] if the game is still at [ply] moves and it is the caller's turn. [remainingMs]
     * is the mover's clock after the move (increment included). [result]/[termination] end the game.
     */
    suspend fun sendMove(gameId: String, ply: Int, uci: String, remainingMs: Long, result: String? = null, termination: String? = null): Result<Unit>

    /** Ends the game if it is still active and still at [ply] moves (resign, timeout, agreed draw, abort). */
    suspend fun finish(gameId: String, ply: Int, result: String, termination: String): Result<Unit>

    suspend fun offerDraw(gameId: String, offer: Boolean): Result<Unit>

    /** Public profile fields at `users/{uid}` (username, onlineElo, puzzle stats …). */
    suspend fun saveProfile(fields: Map<String, Any?>): Result<Unit>
    suspend fun loadProfile(): Result<Map<String, Any?>>

    object Disabled : OnlinePlay {
        private val off = IllegalStateException("Online play is not set up in this build.")
        override val available = false
        override suspend fun ensureSignedIn() = Result.failure<String>(off)
        override fun findMatch(me: OnlineIdentity, tc: TimeControl, filter: RatingFilter): Flow<MatchEvent> = emptyFlow()
        override fun observeGame(gameId: String): Flow<OnlineGame> = emptyFlow()
        override suspend fun sendMove(gameId: String, ply: Int, uci: String, remainingMs: Long, result: String?, termination: String?) = Result.failure<Unit>(off)
        override suspend fun finish(gameId: String, ply: Int, result: String, termination: String) = Result.failure<Unit>(off)
        override suspend fun offerDraw(gameId: String, offer: Boolean) = Result.failure<Unit>(off)
        override suspend fun saveProfile(fields: Map<String, Any?>) = Result.failure<Unit>(off)
        override suspend fun loadProfile() = Result.failure<Map<String, Any?>>(off)
    }
}
