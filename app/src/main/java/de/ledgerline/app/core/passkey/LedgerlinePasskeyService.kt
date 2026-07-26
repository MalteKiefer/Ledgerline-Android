package de.ledgerline.app.core.passkey

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import de.ledgerline.app.R
import de.ledgerline.app.ui.passkey.PasskeyProviderActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-knowledge WebAuthn passkey provider for the Android Credential Manager. Like the autofill
 * service, nothing here touches the vault: `onBeginGetCredentialRequest` returns a single
 * **authentication action** (the vault is locked at rest — VK is wiped on background), so tapping
 * it opens [PasskeyProviderActivity] to unlock, resolve matching passkeys, and complete the
 * ceremony. `onBeginCreateCredentialRequest` returns one create entry that, when chosen, opens the
 * same activity to generate + store a new passkey ([P256Key] + [PasskeyStore]).
 */
class LedgerlinePasskeyService : CredentialProviderService() {

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnknownException())
            return
        }
        val entry = CreateEntry.Builder(
            accountName = getString(R.string.app_name),
            pendingIntent = activityPendingIntent(PasskeyProviderActivity.MODE_CREATE),
        ).build()
        callback.onResult(BeginCreateCredentialResponse(listOf(entry)))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        // Locked provider: a single unlock action. After unlock the activity enumerates the actual
        // passkey candidates and returns a fresh BeginGetCredentialResponse with per-credential entries.
        val action = AuthenticationAction(
            title = getString(R.string.passkey_unlock_action),
            pendingIntent = activityPendingIntent(PasskeyProviderActivity.MODE_GET),
        )
        callback.onResult(BeginGetCredentialResponse(authenticationActions = listOf(action)))
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    private fun activityPendingIntent(mode: String): PendingIntent {
        val intent = Intent(this, PasskeyProviderActivity::class.java)
            .putExtra(PasskeyProviderActivity.EXTRA_MODE, mode)
        return PendingIntent.getActivity(
            this,
            requestCode.getAndIncrement(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        val requestCode = AtomicInteger(1000)
    }
}
