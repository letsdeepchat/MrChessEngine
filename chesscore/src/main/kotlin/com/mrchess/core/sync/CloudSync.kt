package com.mrchess.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A saved game as stored in the cloud (mirrors the local Room row). */
data class CloudGameRecord(
    val id: String,
    val pgn: String,
    val initialFen: String,
    val mode: String,
    val opponentLabel: String,
    val title: String,
    val result: String,
    val whiteAccuracy: Double?,
    val blackAccuracy: Double?,
    val moveClassificationsJson: String,
    val timestamp: Long,
)

/**
 * Optional cloud backup of review history. The app ships with [Disabled]; the Firebase
 * implementation lives in the :cloudsync module and is only built in when a Firebase
 * config file is present.
 */
interface CloudSync {
    val available: Boolean
    /** Signed-in account e-mail, or null. */
    val account: StateFlow<String?>
    /** Signed-in Google profile name, or null. */
    val profileName: StateFlow<String?>

    /** [activity] is an android.app.Activity (kept as Any so this interface stays platform-free). */
    suspend fun signIn(activity: Any): Result<String>
    suspend fun signOut()
    suspend fun push(records: List<CloudGameRecord>)
    suspend fun pull(): List<CloudGameRecord>
    suspend fun delete(id: String)
    suspend fun deleteAll()

    object Disabled : CloudSync {
        override val available = false
        override val account: StateFlow<String?> = MutableStateFlow(null)
        override val profileName: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun signIn(activity: Any) = Result.failure<String>(IllegalStateException("Cloud sync is not configured"))
        override suspend fun signOut() {}
        override suspend fun push(records: List<CloudGameRecord>) {}
        override suspend fun pull(): List<CloudGameRecord> = emptyList()
        override suspend fun delete(id: String) {}
        override suspend fun deleteAll() {}
    }
}
