package com.mrchess.cloudsync

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
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
    private val _name = MutableStateFlow(auth.currentUser?.displayName)

    override val available = true
    override val account: StateFlow<String?> = _account
    override val profileName: StateFlow<String?> = _name

    override suspend fun signIn(activity: Any): Result<String> = runCatching {
        val act = activity as Activity
        val cred = try {
            requestGoogleCredential(act)
        } catch (e: GetCredentialCancellationException) {
            throw SignInException("Sign-in cancelled.", e)
        } catch (e: GetCredentialException) {
            throw SignInException(explain(e), e)
        }
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected credential type" }
        val google = GoogleIdTokenCredential.createFrom(cred.data)
        val user = try {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await().user
        } catch (e: Exception) {
            throw SignInException(
                "Google accepted the account but Firebase refused it (${e.message}). " +
                    "In the Firebase console, check that Authentication → Sign-in method → Google is enabled.", e,
            )
        } ?: error("Sign-in returned no user")
        _name.value = user.displayName ?: google.displayName
        _account.value = user.email ?: google.id
        _account.value!!
    }

    /**
     * Step 1: the bottom-sheet account chooser listing every Google account on the device
     * (not only ones that already authorised this app, and never auto-selected).
     * Step 2: if the device reports no credential, fall back to the "Sign in with Google" flow,
     * which always shows the full Google account picker (and lets the user add an account).
     */
    private suspend fun requestGoogleCredential(act: Activity): Credential {
        val cm = CredentialManager.create(act)
        val idOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setServerClientId(webClientId)
            .build()
        return try {
            cm.getCredential(act, GetCredentialRequest.Builder().addCredentialOption(idOption).build()).credential
        } catch (e: NoCredentialException) {
            val button = GetSignInWithGoogleOption.Builder(webClientId).build()
            cm.getCredential(act, GetCredentialRequest.Builder().addCredentialOption(button).build()).credential
        }
    }

    private fun explain(e: GetCredentialException): String {
        val msg = e.message.orEmpty()
        return when {
            e is NoCredentialException ->
                "No Google account was offered. Add a Google account in Settings → Accounts, then try again. " +
                    "If you already have one, the app's SHA-1 fingerprint is probably not saved in Firebase " +
                    "(Project settings → Your apps → com.mrchess.app → Add fingerprint)."
            "28444" in msg || "28433" in msg || "developer" in msg.lowercase() || "10:" in msg ->
                "Google rejected this app's signature. Save the SHA-1 fingerprint " +
                    "D4:36:F4:E5:35:57:2E:7C:80:B3:D3:02:3A:32:A1:CD:55:A6:82:0D in Firebase under " +
                    "Project settings → Your apps → com.mrchess.app, wait a few minutes, then try again."
            else -> "Sign-in failed: ${msg.ifBlank { e.type }}"
        }
    }

    private class SignInException(message: String, cause: Throwable) : Exception(message, cause)

    override suspend fun signOut() {
        auth.signOut()
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
        _account.value = null
        _name.value = null
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
