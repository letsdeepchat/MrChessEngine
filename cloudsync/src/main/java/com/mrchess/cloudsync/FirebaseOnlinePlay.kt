package com.mrchess.cloudsync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mrchess.core.online.MatchEvent
import com.mrchess.core.online.MatchTicket
import com.mrchess.core.online.Matchmaking
import com.mrchess.core.online.OnlineGame
import com.mrchess.core.online.OnlineIdentity
import com.mrchess.core.online.OnlinePlay
import com.mrchess.core.online.RatingFilter
import com.mrchess.core.online.TimeControl
import com.mrchess.core.rules.Position
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Online play on Cloud Firestore.
 *
 *  matchmaking_queue/{uid}  one ticket per waiting player (time control, rating, accepted range, heartbeat)
 *  online_games/{gameId}    players, clocks, moves (UCI list), status, result, draw offer
 *  users/{uid}              public profile: username, onlineElo, puzzle progress
 *
 * Pairing: scan waiting tickets with the same time control, keep the ones whose rating is in my
 * range and whose range holds mine, then claim the closest one in a transaction that re-reads
 * both tickets (so two players can't grab the same opponent) and creates the game. With nobody
 * to claim, my own ticket waits; a snapshot listener sees it flip to "matched", and I keep
 * re-scanning in case another player queued at the same moment. Security rules: firestore.rules.
 */
class FirebaseOnlinePlay(@Suppress("UNUSED_PARAMETER") context: Context) : OnlinePlay {
    private val auth = FirebaseAuth.getInstance()
    private val db by lazy { FirebaseFirestore.getInstance() }
    private fun queue() = db.collection("matchmaking_queue")
    private fun games() = db.collection("online_games")

    override val available = true

    override suspend fun ensureSignedIn(): Result<String> = runCatching {
        auth.currentUser?.uid ?: try {
            auth.signInAnonymously().await().user?.uid ?: error("Sign-in returned no user")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                CloudErrors.friendly(e) ?: "Couldn't start an online session. Sign in from ⋮ (guest play needs Anonymous sign-in enabled in Firebase).", e,
            )
        }
    }

    override fun findMatch(me: OnlineIdentity, tc: TimeControl, filter: RatingFilter): Flow<MatchEvent> = callbackFlow {
        val range = filter.range(me.rating)
        val mine = MatchTicket(me.uid, me.username, me.rating, tc.id, range.first, range.last, heartbeatMs = System.currentTimeMillis())
        trySend(MatchEvent.Searching(range))
        var matched = false
        val myRef = queue().document(me.uid)

        fun onMatched(gameId: String) {
            if (matched) return
            matched = true
            trySend(MatchEvent.Matched(gameId))
            close()
        }

        val worker = launch {
            try {
                // 1. Someone already waiting? Claim them.
                tryClaim(mine, tc)?.let { onMatched(it); return@launch }
                // 2. Nobody: queue and wait (heartbeat + re-scan for players who queued at the same moment).
                myRef.set(mine.toMap()).await()
                while (!matched) {
                    delay(Matchmaking.HEARTBEAT_MS / 2)
                    if (matched) break
                    tryClaim(mine.copy(heartbeatMs = System.currentTimeMillis()), tc)?.let { onMatched(it); return@launch }
                    runCatching { myRef.update("heartbeatMs", System.currentTimeMillis()).await() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(MatchEvent.Failed(CloudErrors.friendly(e) ?: (e.message ?: "Matchmaking failed")))
                close()
            }
        }
        val reg = myRef.addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists() && snap.getString("status") == MatchTicket.STATUS_MATCHED) snap.getString("gameId")?.let(::onMatched)
        }
        awaitClose {
            reg.remove()
            worker.cancel()
            // Leaving without a game (cancel / screen closed): take the ticket out of the queue.
            if (!matched) launch(NonCancellable) { runCatching { myRef.delete().await() } }
        }
    }

    /** Claims the best compatible waiting ticket; returns the new game id, or null if nobody fits. */
    private suspend fun tryClaim(me: MatchTicket, tc: TimeControl): String? {
        val now = System.currentTimeMillis()
        val waiting = queue()
            .whereEqualTo("timeControl", me.timeControl)
            .whereEqualTo("status", MatchTicket.STATUS_WAITING)
            .limit(50).get().await().documents.mapNotNull { it.toTicket() }
        val candidates = waiting.filter { Matchmaking.compatible(me, it, now) }
            .sortedWith(compareBy<MatchTicket> { kotlin.math.abs(it.rating - me.rating) }.thenBy { it.heartbeatMs })
        for (other in candidates.take(3)) {
            val gameId = games().document().id
            val ok = runCatching {
                db.runTransaction { tx ->
                    val theirs = tx.get(queue().document(other.uid))
                    val mineSnap = tx.get(queue().document(me.uid))
                    if (theirs.getString("status") != MatchTicket.STATUS_WAITING) return@runTransaction false
                    if (mineSnap.exists() && mineSnap.getString("status") != MatchTicket.STATUS_WAITING) return@runTransaction false
                    val iAmWhite = Random.nextBoolean()
                    val w = if (iAmWhite) me else other
                    val b = if (iAmWhite) other else me
                    tx.set(games().document(gameId), mapOf(
                        "whiteUid" to w.uid, "blackUid" to b.uid, "players" to listOf(w.uid, b.uid),
                        "whiteName" to w.username, "blackName" to b.username,
                        "whiteRating" to w.rating, "blackRating" to b.rating,
                        "timeControl" to tc.id, "startFen" to Position.START_FEN, "moves" to emptyList<String>(),
                        "whiteMs" to tc.initialMs, "blackMs" to tc.initialMs,
                        "status" to OnlineGame.STATUS_ACTIVE, "result" to null, "termination" to null, "drawOfferBy" to null,
                        "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
                    ))
                    tx.update(queue().document(other.uid), mapOf("status" to MatchTicket.STATUS_MATCHED, "gameId" to gameId, "matchedBy" to me.uid))
                    if (mineSnap.exists()) tx.update(queue().document(me.uid), mapOf("status" to MatchTicket.STATUS_MATCHED, "gameId" to gameId, "matchedBy" to me.uid))
                    true
                }.await()
            }.getOrDefault(false)
            if (ok) return gameId
        }
        return null
    }

    override fun observeGame(gameId: String): Flow<OnlineGame> = callbackFlow {
        val reg = games().document(gameId).addSnapshotListener { snap, err ->
            if (err != null) { close(IllegalStateException(CloudErrors.friendly(err) ?: err.message, err)); return@addSnapshotListener }
            snap?.toGame()?.let { trySend(it) }
        }
        awaitClose { reg.remove() }
    }

    override suspend fun sendMove(gameId: String, ply: Int, uci: String, remainingMs: Long, result: String?, termination: String?): Result<Unit> = tx(gameId) { g, uid ->
        check(g.status == OnlineGame.STATUS_ACTIVE) { "The game is over." }
        check(g.moves.size == ply) { "The position changed. Try again." }
        val side = g.sideOf(uid) ?: error("You're not in this game.")
        check(g.sideToMove == side) { "It's not your turn." }
        buildMap {
            put("moves", g.moves + uci)
            put(if (side == com.mrchess.core.rules.Side.WHITE) "whiteMs" else "blackMs", remainingMs.coerceAtLeast(0))
            put("drawOfferBy", null)
            if (result != null) { put("status", OnlineGame.STATUS_FINISHED); put("result", result); put("termination", termination) }
        }
    }

    override suspend fun finish(gameId: String, ply: Int, result: String, termination: String): Result<Unit> = tx(gameId) { g, uid ->
        check(g.sideOf(uid) != null) { "You're not in this game." }
        check(g.status == OnlineGame.STATUS_ACTIVE) { "The game is already over." }
        check(g.moves.size == ply) { "The position changed." }
        mapOf("status" to OnlineGame.STATUS_FINISHED, "result" to result, "termination" to termination, "drawOfferBy" to null)
    }

    override suspend fun offerDraw(gameId: String, offer: Boolean): Result<Unit> = tx(gameId) { g, uid ->
        check(g.status == OnlineGame.STATUS_ACTIVE) { "The game is over." }
        mapOf("drawOfferBy" to if (offer) uid else null)
    }

    override suspend fun saveProfile(fields: Map<String, Any?>): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not signed in")
        db.collection("users").document(uid).set(fields + ("updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
        Unit
    }

    override suspend fun loadProfile(): Result<Map<String, Any?>> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not signed in")
        db.collection("users").document(uid).get().await().data.orEmpty()
    }

    /** Reads the game, lets [change] validate it and return the fields to write, in one transaction. */
    private suspend fun tx(gameId: String, change: (OnlineGame, String) -> Map<String, Any?>): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not signed in")
        withContext(NonCancellable) {
            db.runTransaction { t ->
                val ref = games().document(gameId)
                val g = t.get(ref).toGame() ?: error("Game not found")
                t.update(ref, change(g, uid) + ("updatedAt" to FieldValue.serverTimestamp()))
                Unit
            }.await()
        }
    }

    private fun MatchTicket.toMap() = mapOf(
        "uid" to uid, "username" to username, "rating" to rating, "timeControl" to timeControl,
        "minRating" to minRating, "maxRating" to maxRating, "status" to status, "gameId" to gameId,
        "heartbeatMs" to heartbeatMs, "createdAt" to FieldValue.serverTimestamp(),
    )

    private fun DocumentSnapshot.toTicket(): MatchTicket? = runCatching {
        MatchTicket(
            uid = getString("uid") ?: id, username = getString("username") ?: "player",
            rating = getLong("rating")?.toInt() ?: return null, timeControl = getString("timeControl") ?: return null,
            minRating = getLong("minRating")?.toInt() ?: 0, maxRating = getLong("maxRating")?.toInt() ?: 9999,
            status = getString("status") ?: return null, gameId = getString("gameId"), heartbeatMs = getLong("heartbeatMs") ?: 0,
        )
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toGame(): OnlineGame? {
        if (!exists()) return null
        return runCatching {
            OnlineGame(
                id = id,
                whiteUid = getString("whiteUid")!!, blackUid = getString("blackUid")!!,
                whiteName = getString("whiteName") ?: "White", blackName = getString("blackName") ?: "Black",
                whiteRating = getLong("whiteRating")?.toInt() ?: 1000, blackRating = getLong("blackRating")?.toInt() ?: 1000,
                timeControl = getString("timeControl") ?: TimeControl.BLITZ_3.id,
                startFen = getString("startFen") ?: Position.START_FEN,
                moves = (get("moves") as? List<String>).orEmpty(),
                whiteMs = getLong("whiteMs") ?: 0, blackMs = getLong("blackMs") ?: 0,
                status = getString("status") ?: OnlineGame.STATUS_ACTIVE,
                result = getString("result"), termination = getString("termination"), drawOfferBy = getString("drawOfferBy"),
            )
        }.getOrNull()
    }
}
