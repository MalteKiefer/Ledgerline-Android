package de.ledgerline.app.push

import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

/**
 * UnifiedPush v3 receiver. The server sends a display-ready [PushPayload] to the device's endpoint
 * via the user's distributor (e.g. ntfy); we render it as a system notification. No bearer token is
 * needed to receive, so the biometric app-lock stays untouched. Endpoint delivery to the server is
 * delegated to [PushRegistrar] (which defers until the next unlock if we're locked).
 */
@AndroidEntryPoint
class LedgerlinePushService : PushService() {

    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessage(message: PushMessage, instance: String) {
        val payload = PushFilter.parse(message.content) ?: return
        scope.launch {
            if (!PushFilter.shouldShow(settings.pushEnabledNow(), settings.mutedCategoriesNow(), payload.category)) return@launch
            PushNotifier.show(applicationContext, payload, settings.lockscreenContentNow())
        }
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        scope.launch { registrar.onNewEndpoint(applicationContext, endpoint.url) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        scope.launch { registrar.onUnregistered() }
    }

    override fun onUnregistered(instance: String) {
        scope.launch { registrar.onUnregistered() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
