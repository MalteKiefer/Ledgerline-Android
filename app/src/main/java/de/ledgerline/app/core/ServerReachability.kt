package de.ledgerline.app.core

import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the self-hosted server is reachable by pinging its Laravel health endpoint
 * `GET {baseUrl}/up`. On start the app checks this FIRST; if the server can't be reached it runs in
 * **offline mode** (cache-only, no network attempts that would time out). While running it re-pings
 * every 60 s: when the server comes back the app flips to [online] = true and resumes normally.
 *
 * This is server reachability (does OUR host answer), distinct from [de.ledgerline.app.core.offline.
 * Connectivity] (does the device have any internet). Uses the same fail-closed pinned TLS as the API.
 */
@Singleton
class ServerReachability @Inject constructor(
    private val sessionHolder: SessionHolder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    // Optimistic default so the UI never flashes "offline" before the first probe resolves; the
    // monitor's first tick runs immediately and corrects it within a few seconds.
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    @Volatile private var started = false

    /** Start the reachability monitor. Idempotent — call once from the Application. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                _online.value = probe()
                delay(POLL_MS)
            }
        }
    }

    /** One immediate probe (also updates [online]); the app can await this at startup. */
    suspend fun checkNow(): Boolean = probe().also { _online.value = it }

    /** GET {baseUrl}/up → reachable if any HTTP response comes back (2xx/3xx). No session = online. */
    private suspend fun probe(): Boolean {
        val session = sessionHolder.get() ?: return true  // pre-pairing: don't force offline
        val base = session.baseUrl.trimEnd('/')
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = NetworkFactory.pingClient(session.baseUrl, session.spkiPin)
                val req = Request.Builder().url("$base/up").get().build()
                client.newCall(req).execute().use { it.code in 200..399 }
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val POLL_MS = 60_000L
    }
}
