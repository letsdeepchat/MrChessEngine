package com.mrchess.cloudsync

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.mrchess.core.sync.CloudGameRecord
import com.mrchess.core.sync.CloudSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Google Sign-In (Credential Manager) + Firebase Auth + Cloud Firestore, free Spark tier.
 * Games live at users/{uid}/games/{id}. Security rules should only allow a user their own path:
 *
 *   match /users/{uid}/games/{id} { allow read, write: if request.auth != null && request.auth.uid == uid; }
 */
class FirebaseCloudSync(context: Context, private val webClientId: String) : CloudSync {
    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val _account = MutableStateFlow(auth.currentUser?.email)

    override val available = true
    override val account: StateFlow<String?> = _account

    override suspend fun signIn(activity: Any): Result<String> = runCatching {
        val act = activity as Activity
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(act).getCredential(act, request)
        val cred = result.credential
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected credential type" }
        val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
        val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user
            ?: error("Sign-in returned no user")
        _account.value = user.email ?: user.uid
        _account.value!!
    }

    override suspend fun signOut() {
        auth.signOut()
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
        _account.value = null
    }

    private fun games() = db.collection("users").document(auth.currentUser?.uid ?: error("Not signed in")).collection("games")

    override suspend fun push(records: List<CloudGameRecord>) {
        // Firestore batches are limited to 500 writes.
        records.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { r -> batch.set(games().document(r.id), r.toMap()) }
            batch.commit().await()
        }
    }

    override suspend fun pull(): List<CloudGameRecord> =
        games().get().await().documents.mapNotNull { d ->
            runCatching {
                CloudGameRecord(
                    id = d.id,
                    pgn = d.getString("pgn") ?: return@runCatching null,
                    initialFen = d.getString("initialFen") ?: "",
                    mode = d.getString("mode") ?: "",
                    opponentLabel = d.getString("opponentLabel") ?: "",
                    title = d.getString("title") ?: "",
                    result = d.getString("result") ?: "*",
                    whiteAccuracy = d.getDouble("whiteAccuracy"),
                    blackAccuracy = d.getDouble("blackAccuracy"),
                    moveClassificationsJson = d.getString("moveClassificationsJson") ?: "[]",
                    timestamp = d.getLong("timestamp") ?: 0L,
                )
            }.getOrNull()
        }

    override suspend fun delete(id: String) {
        games().document(id).delete().await()
    }

    override suspend fun deleteAll() {
        val docs = games().get().await().documents
        docs.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private fun CloudGameRecord.toMap() = mapOf(
        "pgn" to pgn, "initialFen" to initialFen, "mode" to mode, "opponentLabel" to opponentLabel,
        "title" to title, "result" to result, "whiteAccuracy" to whiteAccuracy, "blackAccuracy" to blackAccuracy,
        "moveClassificationsJson" to moveClassificationsJson, "timestamp" to timestamp,
    )
}
