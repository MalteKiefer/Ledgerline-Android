package de.ledgerline.app.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.service.autofill.Dataset
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.R
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.autofill.DomainMatch
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.core.security.VaultAuthorizers
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.core.security.VaultLocker
import de.ledgerline.app.data.PasswordsRepository
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.domain.model.SecretFields
import de.ledgerline.app.domain.model.SecretItem
import de.ledgerline.app.ui.theme.LedgerlineTheme
import de.ledgerline.app.ui.unlock.UnlockScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Authenticated-Autofill bridge. [LedgerlineAutofillService] returns a single dataset whose
 * authentication points here; the OS launches this activity when the user taps it. Zero-knowledge:
 * the vault is only decrypted after the user authenticates (biometric/passphrase via the app's
 * normal [UnlockScreen]). Two modes:
 *  - FILL: match credentials to the requesting domain/app and return a [Dataset].
 *  - SAVE: persist a newly-entered credential into the unlocked vault.
 */
@AndroidEntryPoint
class AutofillUnlockActivity : FragmentActivity() {

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
        enableEdgeToEdge()

        lifecycleScope.launch {
            idleLocker.setTimeoutMs(settingsStore.timeoutMinutes.first() * 60_000L)
        }

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FILL
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)

        // Lock the vault on background, matching MainActivity/ShareActivity.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (lockGuard.consumeSkip()) return
                locker.lock()
            }
            override fun onResume(owner: LifecycleOwner) {
                if (idleLocker.isExpired()) locker.lock() else idleLocker.touch()
                lockGuard.clear()
            }
        })

        setContent {
            LedgerlineTheme {
                val auth = VaultAuthorizers(
                    activity = this@AutofillUnlockActivity,
                    idleLocker = idleLocker,
                    lockTitle = stringResource(R.string.lock_title),
                    lockSubtitle = stringResource(R.string.lock_subtitle),
                    rememberSubtitle = stringResource(R.string.lock_remember_subtitle),
                    cancelText = stringResource(R.string.action_cancel),
                )
                val unlocked by vaultKeyHolder.unlocked.collectAsStateWithLifecycle()
                if (!unlocked) {
                    UnlockScreen(authorize = auth.authorize, strongAuthorize = auth.strongAuthorize, onUnlocked = {})
                } else if (mode == MODE_SAVE) {
                    handleSave(
                        username = intent.getStringExtra(EXTRA_SAVE_USERNAME),
                        password = intent.getStringExtra(EXTRA_SAVE_PASSWORD),
                        webDomain = webDomain,
                        pkg = pkg,
                    )
                } else {
                    val usernameId = IntentCompat.getParcelableExtra(intent, EXTRA_USERNAME_ID, AutofillId::class.java)
                    val passwordId = IntentCompat.getParcelableExtra(intent, EXTRA_PASSWORD_ID, AutofillId::class.java)
                    AutofillPickerScreen(
                        repo = passwordsRepo,
                        cache = passwordsCache,
                        webDomain = webDomain,
                        packageName = pkg,
                        onPick = { item -> returnDataset(usernameId, passwordId, item) },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    /** Build a [Dataset] from the chosen secret and hand it back to the OS. */
    private fun returnDataset(usernameId: AutofillId?, passwordId: AutofillId?, item: SecretItem) {
        val builder = Dataset.Builder()
        val username = SecretFields.str(item, "username")
        val password = SecretFields.str(item, "password")
        if (usernameId != null && username.isNotEmpty()) builder.setValue(usernameId, AutofillValue.forText(username))
        if (passwordId != null && password.isNotEmpty()) builder.setValue(passwordId, AutofillValue.forText(password))
        val reply = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build())
        setResult(RESULT_OK, reply)
        finish()
    }

    private fun handleSave(username: String?, password: String?, webDomain: String?, pkg: String?) {
        if (password.isNullOrBlank()) { finish(); return }
        val title = DomainMatch.registrableDomain(webDomain) ?: pkg?.substringAfterLast('.') ?: "Login"
        lifecycleScope.launch {
            val values = buildMap {
                username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                put("password", password)
            }
            val urls = listOfNotNull(webDomain?.takeIf { it.isNotBlank() }?.let { "https://$it" })
            val fields = SecretFields.build(kotlinx.serialization.json.JsonObject(emptyMap()), "login", values, urls)
            val now = java.time.Instant.now().toString()
            val item = SecretItem(id = de.ledgerline.app.core.Ids.newId(), type = "login", title = title, fields = fields, created = now, updated = now)
            val res = passwordsRepo.save { m -> m.copy(secrets = m.secrets + item) }
            val msg = if (res is Outcome.Ok) R.string.autofill_saved else R.string.autofill_save_failed
            Toast.makeText(this@AutofillUnlockActivity, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_MODE = "ll.mode"
        private const val MODE_FILL = "fill"
        private const val MODE_SAVE = "save"
        private const val EXTRA_USERNAME_ID = "ll.usernameId"
        private const val EXTRA_PASSWORD_ID = "ll.passwordId"
        private const val EXTRA_WEB_DOMAIN = "ll.webDomain"
        private const val EXTRA_PACKAGE = "ll.package"
        private const val EXTRA_SAVE_USERNAME = "ll.saveUsername"
        private const val EXTRA_SAVE_PASSWORD = "ll.savePassword"

        private val requestCode = AtomicInteger(1)

        /** IntentSender the Autofill framework triggers to authorize + fill. */
        fun authIntentSender(
            context: Context,
            usernameId: AutofillId?,
            passwordId: AutofillId?,
            packageName: String?,
            webDomain: String?,
        ): IntentSender {
            val intent = Intent(context, AutofillUnlockActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_FILL)
                putExtra(EXTRA_USERNAME_ID, usernameId)
                putExtra(EXTRA_PASSWORD_ID, passwordId)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_WEB_DOMAIN, webDomain)
            }
            return PendingIntent.getActivity(
                context,
                requestCode.getAndIncrement(),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            ).intentSender
        }

        /** Intent the service starts to offer saving a newly-entered credential. */
        fun saveIntent(
            context: Context,
            username: String?,
            password: String?,
            packageName: String?,
            webDomain: String?,
        ): Intent = Intent(context, AutofillUnlockActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_SAVE)
            putExtra(EXTRA_SAVE_USERNAME, username)
            putExtra(EXTRA_SAVE_PASSWORD, password)
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_WEB_DOMAIN, webDomain)
        }
    }
}
