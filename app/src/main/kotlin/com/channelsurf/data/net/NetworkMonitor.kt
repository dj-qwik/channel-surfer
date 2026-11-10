package com.channelsurf.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

data class NetworkStatus(val online: Boolean, val metered: Boolean)

class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun snapshot(): NetworkStatus {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkStatus(online = false, metered = false)
        return statusOf(caps)
    }

    private fun statusOf(caps: NetworkCapabilities) = NetworkStatus(
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )

    val status: StateFlow<NetworkStatus> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(statusOf(caps)) }
            override fun onLost(network: Network) { trySend(snapshot()) }
            override fun onUnavailable() { trySend(NetworkStatus(online = false, metered = false)) }
        }
        trySend(snapshot())
        cm.registerDefaultNetworkCallback(cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, snapshot())
}
