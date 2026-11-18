package com.mrchess.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Network checks for the optional cloud features. Everything else in the app works offline and
 * never asks. The permission comes with the cloud module; without it the calls fail quietly and
 * are treated as "online" so the cloud code reports its own error instead.
 */
object Connectivity {
    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /** Calls [block] whenever a network becomes available (used to sync games saved offline). */
    fun onOnline(context: Context, block: () -> Unit) {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = block()
                },
            )
        }
    }

    const val OFFLINE_MESSAGE = "You're offline. Sign-in and backup need the internet; everything else works offline."
}
