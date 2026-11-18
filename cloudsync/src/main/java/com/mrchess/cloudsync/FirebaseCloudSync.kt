package com.mrchess.cloudsync

import androidx.activity.ComponentActivity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
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

    /** Google sign-in / sign-up; see [GoogleAuthHelper] for the three routes it tries. */
    override suspend fun signIn(activity: Any): Result<String> = runCatching {
        val user = GoogleAuthHelper(auth, webClientId).signIn(activity as ComponentActivity)
        _name.value = user.displayName
        _account.value = user.email ?: user.uid
        _account.value!!
    }

    override suspend fun signInWithEmail(email: String, password: String, createAccount: Boolean): Result<String> = runCatching {
        require(email.contains('@')) { "Enter a valid e-mail address." }
        require(password.length >= 6) { "The password needs at least 6 characters." }
        val user = try {
            (if (createAccount) auth.createUserWithEmailAndPassword(email, password) else auth.signInWithEmailAndPassword(email, password)).await().user
        } catch (e: FirebaseAuthUserCollisionException) {
            throw IllegalStateException("An account with this e-mail already exists. Choose \"Sign in\" instead.", e)
        } catch (e: FirebaseAuthWeakPasswordException) {
            throw IllegalStateException("That password is too weak. Use at least 6 characters.", e)
        } catch (e: FirebaseAuthInvalidUserException) {
            throw IllegalStateException("No account with this e-mail. Choose \"Create account\" to sign up.", e)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw IllegalStateException("Wrong e-mail or password. New here? Choose \"Create account\".", e)
        } catch (e: FirebaseAuthException) {
            if (e.errorCode == "ERROR_OPERATION_NOT_ALLOWED")
                throw IllegalStateException("E-mail sign-in is switched off for this app. In the Firebase console enable Authentication → Sign-in method → Email/Password.", e)
            throw IllegalStateException("E-mail sign-in failed: ${e.message}", e)
        } ?: error("Sign-in returned no user")
        _name.value = user.displayName ?: user.email?.substringBefore('@')
        _account.value = user.email ?: user.uid
        _account.value!!
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        require(email.contains('@')) { "Enter your e-mail address first." }
        auth.sendPasswordResetEmail(email).await()
        Unit
    }

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
