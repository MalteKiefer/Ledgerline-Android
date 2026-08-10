package de.ledgerline.app.push

import android.app.Activity
import android.content.Context
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.SettingsStore
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates UnifiedPush registration and delivery of the device endpoint to the server.
 *
 * Delivery is decoupled from the biometric-sealed token: an endpoint can arrive
 * ([onNewEndpoint]) while the app is locked, so it is stashed and flushed on the next unlock
 * ([flushPending]) when the in-memory [SessionHolder] holds the bearer token again.
 */
@Singleton
class PushRegistrar @Inject constructor(
    private val settings: SettingsStore,
    private val account: AccountRepository,
    private val sessionHolder: SessionHolder,
) {
    enum class EnableResult { REGISTERING, NO_DISTRIBUTOR }

    /** Is at least one UnifiedPush distributor (e.g. ntfy) installed? */
    fun hasDistributor(context: Context): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context)

    /**
     * Begin registration. Picks the current/default distributor (or the single installed one) and
     * asks the connector to register; the endpoint arrives asynchronously in the push service.
     * Must be called from an [Activity] (the connector may show a distributor chooser).
     */
    fun enable(activity: Activity): EnableResult {
        if (!hasDistributor(activity)) return EnableResult.NO_DISTRIBUTOR
        PushNotifier.ensureChannels(activity)
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
            if (success) UnifiedPush.register(activity)
        }
        return EnableResult.REGISTERING
    }

    /** Turn push off: unregister from the distributor and clear the server-side endpoint. */
    suspend fun disable(context: Context) {
        runCatching { UnifiedPush.unregister(context) }
        account.clearPushEndpoint()
        settings.setEndpointSent(null)
        settings.setEndpointPending(null)
        settings.setPushDistributor(null)
        settings.setPushEnabled(false)
    }

    /**
     * Handle a fresh endpoint from the connector. Sends it to the server immediately if a session
     * is live; otherwise stashes it to flush on the next unlock. No-op if unchanged from the last
     * successfully-sent endpoint.
     */
    suspend fun onNewEndpoint(context: Context, url: String) {
        UnifiedPush.getAckDistributor(context)?.let { settings.setPushDistributor(it) }
        if (url == settings.endpointSentNow()) return
        if (sessionHolder.get() != null && account.registerPushEndpoint(url)) {
            settings.setEndpointSent(url)
            settings.setEndpointPending(null)
        } else {
            settings.setEndpointPending(url)
        }
    }

    /** The distributor unregistered us (removed/reset). Drop local state so the UI reflects it. */
    suspend fun onUnregistered() {
        account.clearPushEndpoint()
        settings.setEndpointSent(null)
        settings.setEndpointPending(null)
        settings.setPushEnabled(false)
    }

    /** Called on unlock: deliver any endpoint that arrived while we couldn't authenticate. */
    suspend fun flushPending() {
        val pending = settings.endpointPendingNow() ?: return
        if (sessionHolder.get() == null) return
        if (account.registerPushEndpoint(pending)) {
            settings.setEndpointSent(pending)
            settings.setEndpointPending(null)
        }
    }
}
