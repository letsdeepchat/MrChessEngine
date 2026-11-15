package com.mrchess.app.data

import android.content.Context
import com.mrchess.core.review.GameSummary
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Pgn
import com.mrchess.core.sync.CloudGameRecord
import com.mrchess.core.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * Offline-first review history. Room is the source of truth; when a cloud account is signed
 * in, new or changed games are pushed and remote games are merged back (deduplicated by id).
 */
class ReviewRepository(private val context: Context, val cloud: CloudSync) {
    private val dao = ReviewDatabase.get(context).games()

    val games: Flow<List<GameRecordEntity>> = dao.observeAll()

    /** Inserts or updates a game; returns its id. Accuracy/badges come from the timeline's annotations. */
    suspend fun save(t: GameTimeline, mode: String, id: String? = null): String = withContext(Dispatchers.IO) {
        val recId = id ?: UUID.randomUUID().toString()
        val existing = id?.let { dao.get(it) }
        val summary = GameSummary.of(t.nodes.map { it.mover to it.annotation })
        val game = t.toGame()
        val white = t.tags["White"] ?: "White"
        val black = t.tags["Black"] ?: "Black"
        val opponent = when {
            white == "You" -> black
            black == "You" -> white
            else -> "$white vs $black"
        }
        val e = GameRecordEntity(
            id = recId,
            pgn = game.toPgn(),
            initialFen = t.startFen,
            mode = mode,
            opponentLabel = opponent,
            title = "$white vs $black",
            result = game.result.pgn,
            whiteAccuracy = summary.accuracyWhite ?: existing?.whiteAccuracy,
            blackAccuracy = summary.accuracyBlack ?: existing?.blackAccuracy,
            moveClassificationsJson = JSONArray(t.nodes.map { it.moveQualityBadge?.name ?: "" }).toString(),
            timestamp = existing?.timestamp ?: System.currentTimeMillis(),
            isSynced = false,
        )
        dao.upsert(e)
        syncQuietly()
        recId
    }

    suspend fun load(id: String): GameTimeline? = withContext(Dispatchers.IO) {
        dao.get(id)?.let { runCatching { GameTimeline.fromGame(Pgn.parse(it.pgn), atEnd = false) }.getOrNull() }
    }

    suspend fun delete(id: String, alsoCloud: Boolean = true) = withContext(Dispatchers.IO) {
        dao.delete(id)
        if (alsoCloud && cloud.account.value != null) runCatching { cloud.delete(id) }
    }

    /** "Clear History": local only, or local and cloud. */
    suspend fun clear(alsoCloud: Boolean) = withContext(Dispatchers.IO) {
        dao.clear()
        if (alsoCloud && cloud.account.value != null) runCatching { cloud.deleteAll() }
    }

    /** Push unsynced games and merge remote ones. No-op when signed out or offline. */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!cloud.available || cloud.account.value == null) return@withContext Result.success(Unit)
        runCatching {
            val local = dao.unsynced()
            if (local.isNotEmpty()) {
                cloud.push(local.map { it.toCloud() })
                dao.markSynced(local.map { it.id })
            }
            val remote = cloud.pull()
            val merged = remote.filter { r -> dao.get(r.id)?.let { it.timestamp < r.timestamp || it.isSynced } ?: true }
            if (merged.isNotEmpty()) dao.upsertAll(merged.map { it.toEntity() })
        }
    }

    private suspend fun syncQuietly() { runCatching { sync() } }

    /** One-time import of games saved as PGN files by earlier versions. */
    suspend fun migrateLegacyPgnFiles() = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "games")
        dir.listFiles { f -> f.extension == "pgn" }.orEmpty().forEach { f ->
            runCatching {
                val g = Pgn.parse(f.readText())
                save(GameTimeline.fromGame(g), mode = "PLAY_AI")
                f.delete()
            }
        }
    }

    private fun GameRecordEntity.toCloud() = CloudGameRecord(
        id, pgn, initialFen, mode, opponentLabel, title, result, whiteAccuracy, blackAccuracy, moveClassificationsJson, timestamp,
    )

    private fun CloudGameRecord.toEntity() = GameRecordEntity(
        id, pgn, initialFen, mode, opponentLabel, title, result, whiteAccuracy, blackAccuracy, moveClassificationsJson, timestamp, isSynced = true,
    )

    companion object {
        /** Loads the Firebase implementation if this build includes it (see app/build.gradle.kts). */
        fun createCloudSync(context: Context): CloudSync = runCatching {
            if (!com.mrchess.app.BuildConfig.CLOUD_SYNC) return CloudSync.Disabled
            // Must be the *web* client (type 3), never the Android client (type 1).
            val res = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            val webClientId = (if (res != 0) context.getString(res) else "").ifBlank { com.mrchess.app.BuildConfig.WEB_CLIENT_ID }
            if (webClientId.isBlank()) return CloudSync.Disabled
            val cls = Class.forName("com.mrchess.cloudsync.FirebaseCloudSync")
            cls.getConstructor(Context::class.java, String::class.java)
                .newInstance(context, webClientId) as CloudSync
        }.getOrDefault(CloudSync.Disabled)
    }
}
