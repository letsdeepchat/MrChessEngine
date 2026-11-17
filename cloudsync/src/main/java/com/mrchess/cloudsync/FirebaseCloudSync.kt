package com.mrchess.cloudsync

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.mrchess.core.sync.CloudGameRecord
import com.mrchess.core.sync.CloudSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

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

    /**
     * Sign in / sign up with Google, trying three routes so the account chooser always gets a chance:
     *  1. Credential Manager "Sign in with Google" (full account picker, works for first-time users);
     *  2. the Play services Google Sign-In account picker;
     *  3. Firebase's hosted Google OAuth page in a Custom Tab.
     * Routes 1 and 2 need the APK's SHA-1 registered in Firebase. Route 3 avoids the Play services
     * client but Firebase still checks the app's package and certificate on the way back.
     */
    override suspend fun signIn(activity: Any): Result<String> = runCatching {
        val act = activity as ComponentActivity
        val problems = mutableListOf<String>()

        // Tier 1: Credential Manager.
        val idToken: String? = try {
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val cred = CredentialManager.create(act).getCredential(act, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
            require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected credential type" }
            GoogleIdTokenCredential.createFrom(cred.data).idToken
        } catch (e: GetCredentialCancellationException) {
            throw SignInException("Sign-in cancelled.", e)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            problems += "Credential Manager: ${e.message ?: e.javaClass.simpleName}"
            // Tier 2: Play services account picker.
            try {
                playServicesIdToken(act)
            } catch (e2: CancelledException) {
                throw SignInException("Sign-in cancelled.", e2)
            } catch (e2: Exception) {
                if (e2 is kotlinx.coroutines.CancellationException) throw e2
                problems += "Google Sign-In: ${e2.message ?: e2.javaClass.simpleName}"
                null
            }
        }

        val user = if (idToken != null) {
            try {
                auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user
            } catch (e: Exception) {
                throw SignInException(
                    "Google accepted the account but Firebase refused it (${e.message}). " +
                        "In the Firebase console, check that Authentication → Sign-in method → Google is enabled.", e,
                )
            }
        } else {
            // Tier 3: Firebase-hosted Google OAuth in a Custom Tab.
            try {
                val provider = OAuthProvider.newBuilder("google.com").addCustomParameter("prompt", "select_account").build()
                val pending = auth.pendingAuthResult
                (pending ?: auth.startActivityForSignInWithProvider(act, provider)).await().user
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                problems += "Browser sign-in: ${e.message ?: e.javaClass.simpleName}"
                throw SignInException(explain(problems), e)
            }
        } ?: error("Sign-in returned no user")
        _name.value = user.displayName
        _account.value = user.email ?: user.uid
        _account.value!!
    }

    private class CancelledException : Exception("cancelled")

    /** Tier 2: the classic Play services account chooser, launched through the activity result registry. */
    private suspend fun playServicesIdToken(act: ComponentActivity): String {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(act, gso)
        runCatching { client.signOut().await() } // always show the chooser, never silently reuse an account
        val result = suspendCancellableCoroutine<ActivityResult> { cont ->
            lateinit var launcher: ActivityResultLauncher<Intent>
            launcher = act.activityResultRegistry.register("mrchess-google-${System.nanoTime()}", ActivityResultContracts.StartActivityForResult()) { r ->
                launcher.unregister()
                if (cont.isActive) cont.resume(r)
            }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(client.signInIntent)
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            return account.idToken ?: error("Google returned no ID token")
        } catch (e: ApiException) {
            if (e.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) throw CancelledException()
            throw IllegalStateException("status ${e.statusCode}" + when (e.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> " (developer error: this APK's SHA-1 is not registered for com.mrchess.app)"
                GoogleSignInStatusCodes.SIGN_IN_FAILED -> " (sign-in failed: usually the SHA-1 or the OAuth consent screen)"
                CommonStatusCodes.NETWORK_ERROR -> " (no internet connection)"
                else -> ""
            }, e)
        }
    }

    private fun explain(problems: List<String>): String {
        val all = problems.joinToString(" ")
        val config = listOf("10", "12500", "28444", "28433", "developer", "No credentials", "SHA-1", "INVALID_CERT", "app_not_authorized", "not authorized")
            .any { all.contains(it, ignoreCase = true) }
        return buildString {
            if (all.contains("network", ignoreCase = true)) append("Sign-in needs an internet connection. ")
            if (config || !all.contains("network", ignoreCase = true)) {
                append("Google refused this app's signing certificate. In the Firebase console open Project settings → Your apps → ")
                append("com.mrchess.app → Add fingerprint, paste SHA-1 D4:36:F4:E5:35:57:2E:7C:80:B3:D3:02:3A:32:A1:CD:55:A6:82:0D, ")
                append("save, wait about 5 minutes and try again. Also check Authentication → Sign-in method → Google is enabled. ")
            }
            append("You can keep using every feature without signing in.")
            append("\n\nDetails: ").append(problems.joinToString("; "))
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
