package com.mrchess.cloudsync

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume


/**
 * Google sign-in / sign-up for Firebase Auth with three routes tried in order, so the Google
 * account chooser always gets a chance to open:
 *  1. Credential Manager with GetSignInWithGoogleOption (full account picker, first-time users too);
 *  2. the Play services GoogleSignInClient account picker (signInIntent);
 *  3. Firebase's hosted Google OAuth page in a Chrome Custom Tab (OAuthProvider "google.com").
 * Google still checks who is asking on every route: routes 1 and 2 need the APK's SHA-1 in the
 * Firebase project, and route 3 needs the package's SHA-1/SHA-256 and the Google provider enabled.
 */
class GoogleAuthHelper(private val auth: FirebaseAuth, private val webClientId: String) {

    suspend fun signIn(act: ComponentActivity): FirebaseUser {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SignInException(
                    CloudErrors.friendly(e)?.let { "Google accepted your account, but Firebase refused it. $it" }
                        ?: ("Google accepted the account but Firebase refused it (${e.message}). " +
                            "In the Firebase console, check that Authentication → Sign-in method → Google is enabled."),
                    e,
                )
            }
        } else {
            // Tier 3: Firebase-hosted Google OAuth in a Custom Tab.
            try {
                val provider = OAuthProvider.newBuilder("google.com")
                    .addCustomParameter("prompt", "select_account")
                    .setScopes(listOf("email", "profile"))
                    .build()
                val pending = auth.pendingAuthResult
                (pending ?: auth.startActivityForSignInWithProvider(act, provider)).await().user
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                problems += "Browser sign-in: ${e.message ?: e.javaClass.simpleName}"
                throw SignInException(explain(problems), e)
            }
        } ?: error("Sign-in returned no user")
        return user
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
        // A project setting (Authentication not started, provider off, …) explains every route failing.
        CloudErrors.friendly(IllegalStateException(all))?.let { return "$it\n\nDetails: ${problems.joinToString("; ")}" }
        val config = listOf("10", "12500", "28444", "28433", "developer", "No credentials", "SHA-1", "INVALID_CERT", "app_not_authorized", "not authorized")
            .any { all.contains(it, ignoreCase = true) }
        return buildString {
            if (all.contains("network", ignoreCase = true)) append("Sign-in needs an internet connection. ")
            if (config || !all.contains("network", ignoreCase = true)) {
                append("Google didn't accept this app's sign-in setup. In the Firebase console check three things: ")
                append("1) Authentication → Sign-in method → Google is enabled with a support e-mail; ")
                append("2) Project settings → Your apps → com.mrchess.app lists SHA-1 D4:36:F4:E5:35:57:2E:7C:80:B3:D3:02:3A:32:A1:CD:55:A6:82:0D ")
                append("and SHA-256 A3:C0:89:B8:57:A2:3E:13:DF:15:19:9E:52:62:85:D7:CE:AB:62:B2:0D:D3:F4:2F:53:C5:68:DE:10:84:15:12; ")
                append("3) the phone has a Google account added. Changes can take about 5 minutes to reach Google. ")
            }
            append("You can keep using every feature without signing in.")
            append("\n\nDetails: ").append(problems.joinToString("; "))
        }
    }

    class SignInException(message: String, cause: Throwable) : Exception(message, cause)
}
