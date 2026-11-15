package com.mrchess.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One finished or reviewed game. Kept until the user deletes it or clears app storage. */
@Entity(tableName = "games")
data class GameRecordEntity(
    @PrimaryKey val id: String,
    val pgn: String,
    val initialFen: String,
    /** SessionMode name the game came from. */
    val mode: String,
    val opponentLabel: String,
    val title: String,
    val result: String,
    val whiteAccuracy: Double?,
    val blackAccuracy: Double?,
    /** JSON array of MoveQuality names per move ("" for unanalysed moves). */
    val moveClassificationsJson: String,
    val timestamp: Long,
    val isSynced: Boolean = false,
)

@Dao
interface GameRecordDao {
    @Query("SELECT * FROM games ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<GameRecordEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: String): GameRecordEntity?

    @Query("SELECT * FROM games WHERE isSynced = 0")
    suspend fun unsynced(): List<GameRecordEntity>

    @Upsert
    suspend fun upsert(e: GameRecordEntity)

    @Upsert
    suspend fun upsertAll(e: List<GameRecordEntity>)

    @Query("UPDATE games SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM games")
    suspend fun clear()
}

@Database(entities = [GameRecordEntity::class], version = 1, exportSchema = false)
abstract class ReviewDatabase : RoomDatabase() {
    abstract fun games(): GameRecordDao

    companion object {
        @Volatile private var instance: ReviewDatabase? = null
        fun get(context: Context): ReviewDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReviewDatabase::class.java, "review_history.db")
                .build().also { instance = it }
        }
    }
}
