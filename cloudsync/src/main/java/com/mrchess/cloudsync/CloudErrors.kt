package com.mrchess.cloudsync

import com.google.firebase.FirebaseNetworkException

/**
 * Turns Firebase's raw errors into a sentence that says what to fix. Most sign-in failures are
 * project settings in the Firebase console rather than anything in the app, so the message names
 * the exact switch.
 */
internal object CloudErrors {
    fun friendly(e: Throwable): String? {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val m = chain.mapNotNull { it.message }.joinToString(" | ")
        val upper = m.uppercase()
        return when {
            "CONFIGURATION_NOT_FOUND" in upper ->
                "Sign-in isn't switched on for this app's Firebase project yet. In the Firebase console open " +
                    "Authentication and press Get started, then under Sign-in method enable Email/Password and Google."
            "OPERATION_NOT_ALLOWED" in upper || "PASSWORD_LOGIN_DISABLED" in upper ->
                "This sign-in method is switched off. In the Firebase console enable it under Authentication → Sign-in method."
            "DOES NOT EXIST FOR PROJECT" in upper || ("NOT_FOUND" in upper && "DATABASE" in upper) ->
                "The cloud database hasn't been created yet. In the Firebase console open Firestore Database and press Create database."
            "PERMISSION_DENIED" in upper || "MISSING OR INSUFFICIENT PERMISSIONS" in upper ->
                "The cloud database refused access. In Firestore → Rules, allow users/{uid}/games for the signed-in user."
            "TOO_MANY_ATTEMPTS" in upper || "BLOCKED ALL REQUESTS" in upper ->
                "Too many attempts from this device. Wait a few minutes and try again."
            chain.any { it is FirebaseNetworkException } || "NETWORK ERROR" in upper || "UNABLE TO RESOLVE HOST" in upper ->
                "No connection to Google. Check your internet connection and try again."
            "API KEY NOT VALID" in upper || "API_KEY_INVALID" in upper ->
                "Firebase rejected this app's API key. Download google-services.json again from Project settings."
            else -> null
        }
    }
}
