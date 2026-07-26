package de.ledgerline.app.ui.passkey

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.R
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.passkey.PasskeyRequests
import de.ledgerline.app.core.passkey.PasskeyResponses
import de.ledgerline.app.core.passkey.PasskeyStore
import de.ledgerline.app.core.passkey.P256Key
import de.ledgerline.app.core.passkey.WebAuthnCbor
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.core.security.VaultAuthorizers
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.core.security.VaultLocker
import de.ledgerline.app.data.PasswordsRepository
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.domain.model.SecretItem
import de.ledgerline.app.ui.theme.LedgerlineTheme
import de.ledgerline.app.ui.unlock.UnlockScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Completes a WebAuthn passkey ceremony started by [de.ledgerline.app.core.passkey
 * .LedgerlinePasskeyService]. Unlock-gated (reuses the app's [UnlockScreen] + [VaultAuthorizers]
 * like the autofill/share entry points) so the vault is only ever decrypted after the user
 * authenticates. Three modes:
 *  - CREATE: generate a P-256 passkey, store it, return the registration response.
 *  - GET: after unlock, resolve matching passkeys and return per-credential entries.
 *  - ASSERT: sign the selected passkey's assertion and return the authentication response.
 */
@AndroidEntryPoint
class PasskeyProviderActivity : FragmentActivity() {

    @Inject lateinit var vaultKeyHolder: VaultKeyHolder
    @Inject lateinit var idleLocker: IdleLocker
    @Inject lateinit var locker: VaultLocker
    @Inject lateinit var lockGuard: LockGuard
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var passwordsRepo: PasswordsRepository
    @Inject lateinit var passwordsCache: PasswordsCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch { idleLocker.setTimeoutMs(settingsStore.timeoutMinutes.first() * 60_000L) }

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_GET

        setContent {
            LedgerlineTheme {
                val auth = VaultAuthorizers(
                    activity = this@PasskeyProviderActivity,
                    idleLocker = idleLocker,
                    lockTitle = stringResource(R.string.lock_title),
                    lockSubtitle = stringResource(R.string.lock_subtitle),
                    rememberSubtitle = stringResource(R.string.lock_remember_subtitle),
                    cancelText = stringResource(R.string.action_cancel),
                )
                val unlocked by vaultKeyHolder.unlocked.collectAsStateWithLifecycle()
                if (!unlocked) {
                    UnlockScreen(authorize = auth.authorize, strongAuthorize = auth.strongAuthorize, onUnlocked = {})
                } else {
                    LaunchedEffect(Unit) { runCatching { handle(mode) }.onFailure { fail(mode) } }
                }
            }
        }
    }

    private suspend fun secrets(): List<SecretItem> {
        if (passwordsCache.value.value == null) passwordsRepo.load()
        return passwordsCache.value.value?.manifest?.secrets.orEmpty()
    }

    private suspend fun handle(mode: String) {
        when (mode) {
            MODE_CREATE -> handleCreate()
            MODE_GET -> handleEnumerate()
            MODE_ASSERT -> handleAssert()
            else -> finish()
        }
    }

    // --- CREATE ----------------------------------------------------------------

    private suspend fun handleCreate() {
        val req = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val calling = req?.callingRequest as? CreatePublicKeyCredentialRequest ?: return fail(MODE_CREATE)
        val opts = PasskeyRequests.parseCreate(calling.requestJson) ?: return fail(MODE_CREATE)
        if (!PasskeyStore.rpIdAllowed(opts.rpId)) return fail(MODE_CREATE)

        val kp = P256Key.generate()
        val credentialId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cose = WebAuthnCbor.cosePublicKey(kp.x, kp.y)
        val authData = WebAuthnCbor.authDataForCreate(opts.rpId, credentialId, cose)
        val attObj = WebAuthnCbor.attestationObjectNone(authData)
        val clientDataJson = clientDataOrEmpty(calling.clientDataHash, "webauthn.create", opts.challengeB64Url, opts.rpId)

        val now = Instant.now().toString()
        val item = PasskeyStore.standaloneItem(
            rpId = opts.rpId, rpName = opts.rpName, credentialId = credentialId,
            privateKeyJwk = kp.privateJwk, publicKeyJwk = kp.publicJwk, userHandle = opts.userId,
            userName = opts.userName, userDisplayName = opts.userDisplayName, now = now,
        )
        val saved = passwordsRepo.save { m -> m.copy(secrets = m.secrets + item) }
        if (saved is Outcome.Err) return fail(MODE_CREATE)

        val json = PasskeyResponses.registration(credentialId, attObj, clientDataJson)
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(json))
        setResult(RESULT_OK, result)
        finish()
    }

    // --- GET (enumerate after unlock) ------------------------------------------

    private suspend fun handleEnumerate() {
        val beginReq = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent) ?: return fail(MODE_GET)
        val all = secrets()
        val entries = ArrayList<PublicKeyCredentialEntry>()
        for (option in beginReq.beginGetCredentialOptions) {
            val pk = option as? BeginGetPublicKeyCredentialOption ?: continue
            val get = PasskeyRequests.parseGet(pk.requestJson) ?: continue
            var cands = PasskeyStore.candidates(get.rpId, all)
            if (get.allowCredentialIds.isNotEmpty()) {
                val allow = get.allowCredentialIds.map { it.toList() }
                cands = cands.filter { it.credentialId.toList() in allow }
            }
            for (c in cands) {
                val entry = PublicKeyCredentialEntry.Builder(
                    context = this,
                    username = c.userName.ifBlank { c.rpId },
                    pendingIntent = assertPendingIntent(c.credentialId),
                    beginGetPublicKeyCredentialOption = pk,
                ).setDisplayName(c.userDisplayName.ifBlank { c.rpId }).build()
                entries.add(entry)
            }
        }
        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, BeginGetCredentialResponse(credentialEntries = entries))
        setResult(RESULT_OK, result)
        finish()
    }

    // --- ASSERT (sign the selected passkey) ------------------------------------

    private suspend fun handleAssert() {
        val req = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: return fail(MODE_ASSERT)
        val option = req.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull() ?: return fail(MODE_ASSERT)
        val get = PasskeyRequests.parseGet(option.requestJson) ?: return fail(MODE_ASSERT)
        val wantB64 = intent.getStringExtra(EXTRA_CRED_ID) ?: return fail(MODE_ASSERT)
        val wantId = runCatching { P256Key.b64uDecode(wantB64) }.getOrNull()?.toList() ?: return fail(MODE_ASSERT)

        val pk = PasskeyStore.candidates(get.rpId, secrets()).firstOrNull { it.credentialId.toList() == wantId }
            ?: return fail(MODE_ASSERT)

        val authData = WebAuthnCbor.authDataForAssert(get.rpId)
        val clientDataJson = clientDataOrEmpty(option.clientDataHash, "webauthn.get", get.challengeB64Url, get.rpId)
        val clientDataHash = option.clientDataHash ?: sha256(clientDataJson)
        val signature = P256Key.sign(pk.privateKeyJwk, authData + clientDataHash)

        val json = PasskeyResponses.authentication(pk.credentialId, authData, signature, clientDataJson, pk.userHandle)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PublicKeyCredential(json)))
        setResult(RESULT_OK, result)
        finish()
    }

    // --- helpers ---------------------------------------------------------------

    /**
     * When the caller supplied a `clientDataHash` (browser/privileged), we sign over that hash and
     * leave the response's clientDataJSON empty (the caller owns it). Otherwise (app caller) we
     * build clientDataJSON from the challenge + a best-effort origin.
     */
    private fun clientDataOrEmpty(clientDataHash: ByteArray?, type: String, challengeB64Url: String, rpId: String): ByteArray =
        if (clientDataHash != null) ByteArray(0)
        else PasskeyResponses.clientDataJson(type, challengeB64Url, "https://$rpId")

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun assertPendingIntent(credentialId: ByteArray): PendingIntent {
        val intent = Intent(this, PasskeyProviderActivity::class.java)
            .putExtra(EXTRA_MODE, MODE_ASSERT)
            .putExtra(EXTRA_CRED_ID, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId))
        return PendingIntent.getActivity(
            this, requestCode.getAndIncrement(), intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun fail(mode: String) {
        val result = Intent()
        when (mode) {
            MODE_CREATE -> PendingIntentHandler.setCreateCredentialException(result, CreateCredentialUnknownException())
            else -> PendingIntentHandler.setGetCredentialException(result, GetCredentialUnknownException())
        }
        Toast.makeText(this, R.string.passkey_error, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        const val EXTRA_MODE = "ll.passkey.mode"
        const val MODE_CREATE = "create"
        const val MODE_GET = "get"
        const val MODE_ASSERT = "assert"
        private const val EXTRA_CRED_ID = "ll.passkey.credId"
        private val requestCode = AtomicInteger(5000)
    }
}
