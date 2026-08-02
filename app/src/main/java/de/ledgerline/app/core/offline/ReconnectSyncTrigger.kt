package de.ledgerline.app.core.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the offline write outbox the instant the network comes back, rather than waiting for the
 * next [BackgroundSync] tick (which, with auto-refresh set to 0, is slow/never). Registers a single
 * process-lifetime [ConnectivityManager.NetworkCallback]; on a validated network it kicks
 * [OfflineSyncEngine.syncNow], which self-gates on locked/offline/empty so spurious callbacks are
 * cheap no-ops. AOSP only — no Play Services.
 */
@Singleton
class ReconnectSyncTrigger @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val syncEngine: OfflineSyncEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile private var started = false

    /** Idempotent — call once from the Application. */
    fun start() {
        if (started) return
        started = true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = drain()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Only once the network is actually validated (avoids captive-portal false starts).
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) drain()
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun drain() {
        scope.launch { runCatching { syncEngine.syncNow() } }
    }
}
