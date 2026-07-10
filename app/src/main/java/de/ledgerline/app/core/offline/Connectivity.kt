package de.ledgerline.app.core.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow seam over the platform network state so repositories can decide
 * network-first vs cache-fallback without depending on Android APIs (JVM tests
 * supply a fake). AOSP only — no Google Play Services.
 */
interface Connectivity {
    fun isOnline(): Boolean

    /** True if the active network is unmetered (Wi-Fi / Ethernet). False when offline. */
    fun isUnmetered(): Boolean
}

@Singleton
class AndroidConnectivity @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Connectivity {
    override fun isOnline(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isUnmetered(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
