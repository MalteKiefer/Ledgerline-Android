package de.ledgerline.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.R
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.security.AppLock
import de.ledgerline.app.core.security.CryptoAuth
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.core.security.VaultLocker
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.ui.theme.LedgerlineTheme
import de.ledgerline.app.ui.unlock.UnlockScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Android share target. Receives ACTION_SEND / ACTION_SEND_MULTIPLE, parses and
 * classifies the shared items (image/video -> Gallery, else Files), and hosts a
 * small unlock-gated Compose flow. Like MainActivity it wipes the Vault Key on
 * background (respecting in-flight background ops) and is FLAG_SECURE.
 *
 * S1: intent parse + unlock gate + lock lifecycle + a placeholder confirm.
 * The real confirm sheet and upload land in S2.
 */
@AndroidEntryPoint
class ShareActivity : FragmentActivity() {

    @Inject lateinit var vaultKeyHolder: VaultKeyHolder
    @Inject lateinit var sessionHolder: SessionHolder
    @Inject lateinit var idleLocker: IdleLocker
    @Inject lateinit var locker: VaultLocker
    @Inject lateinit var lockGuard: LockGuard
    @Inject lateinit var operationManager: OperationManager
    @Inject lateinit var settingsStore: SettingsStore
    private val appLock = AppLock()

    private var items: List<SharedItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MASVS-STORAGE: block screenshots, screen recording, recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // Apply the persisted idle-lock timeout before any unlock can happen.
        idleLocker.timeoutMs = runBlocking { settingsStore.timeoutMinutes.first() } * 60_000L

        items = parseIntent(intent)
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.share_none, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Skip exactly one auto-lock when WE launched a system picker or
                // credential prompt, which briefly backgrounds us.
                if (lockGuard.consumeSkip()) return
                // Defer the wipe while a background-enabled op is running: the op
                // keeps the VK alive and the OperationManager wipes when it drains.
                if (operationManager.isBackgroundEnabled() && operationManager.hasActive()) {
                    operationManager.onAppBackground()
                } else {
                    locker.lock()
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                operationManager.onAppForeground()
                // Idle wipe is deferred while an op runs — idle does not abort it.
                if (idleLocker.isExpired() && !operationManager.hasActive()) {
                    locker.lock()
                } else idleLocker.touch()
                lockGuard.clear()
            }
        })

        setContent {
            LedgerlineTheme {
                val lockTitle = stringResource(R.string.lock_title)
                val lockSubtitle = stringResource(R.string.lock_subtitle)
                // Runs ONE CryptoObject-bound biometric on the keystore cipher and
                // returns the authorised cipher (or null on cancel/failure).
                val authorize: suspend (javax.crypto.Cipher) -> javax.crypto.Cipher? = { cipher ->
                    idleLocker.touch()
                    when (
                        val r = appLock.authenticate(
                            this@ShareActivity, lockTitle, lockSubtitle, BiometricPrompt.CryptoObject(cipher),
                        )
                    ) {
                        is CryptoAuth.Success -> r.cipher
                        else -> null
                    }
                }

                val unlocked by vaultKeyHolder.unlocked.collectAsStateWithLifecycle()
                if (!unlocked) {
                    UnlockScreen(authorize = authorize, onUnlocked = {})
                } else {
                    val shareVm: ShareViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    // Surface the import summary (imported/failed counts) as a Toast that
                    // outlives finish(). Consumed once, then the message is cleared.
                    val message by shareVm.message.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(message) {
                        message?.let { m ->
                            toastFor(m)?.let { Toast.makeText(this@ShareActivity, it, Toast.LENGTH_SHORT).show() }
                            shareVm.clearMessage()
                        }
                    }
                    ShareScreen(items = items, vm = shareVm, onDone = { finish() })
                }
            }
        }

        // Belt-and-suspenders: force light status bar icons for the dark UI.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
    }

    /** Parses ACTION_SEND / ACTION_SEND_MULTIPLE into classified [SharedItem]s. */
    private fun parseIntent(intent: Intent): List<SharedItem> {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { listOf(it) } ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()
            else -> emptyList()
        }
        return uris.map { uri ->
            val mime = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            SharedItem(uri = uri, mime = mime, name = displayName(uri), target = classify(mime))
        }
    }

    /** Best-effort display name via OpenableColumns; falls back to "shared". */
    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) return it }
                    }
                }
        }
        return "shared"
    }

    /** Turns a `"import_done:<ok>:<failed>"` message into a localized summary, or null. */
    private fun toastFor(message: String): String? {
        val parts = message.split(":")
        if (parts.size != 3 || parts[0] != "import_done") return null
        val ok = parts[1].toIntOrNull() ?: return null
        val failed = parts[2].toIntOrNull() ?: 0
        val base = getString(R.string.share_imported, ok)
        return if (failed > 0) "$base — ${getString(R.string.share_import_failed, failed)}" else base
    }
}
