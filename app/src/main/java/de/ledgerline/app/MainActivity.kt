package de.ledgerline.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.security.AppLock
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.LockResult
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.ui.nav.AppNav
import de.ledgerline.app.ui.theme.LedgerlineTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Single activity. FragmentActivity is required by BiometricPrompt. The Vault Key
 * is wiped whenever the app is backgrounded (ON_STOP) and when it returns after
 * the idle timeout — it never survives in memory past a lock.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var vaultKeyHolder: VaultKeyHolder
    @Inject lateinit var idleLocker: IdleLocker
    @Inject lateinit var sessionHolder: SessionHolder
    @Inject lateinit var workspaceCache: WorkspaceCache
    @Inject lateinit var settingsStore: SettingsStore
    private val appLock = AppLock()

    // Emits the latest validated pairing deep link. singleTask means a link
    // delivered while running arrives via onNewIntent, not a fresh onCreate.
    private val pairLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MASVS-STORAGE: block screenshots, screen recording, recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        // Apply the persisted idle-lock timeout before any unlock can happen.
        idleLocker.timeoutMs = runBlocking { settingsStore.timeoutMinutes.first() } * 60_000L

        // Cold-start: accept a validated pairing deep link from the launch intent.
        pairLink.value = extractPairLink(intent)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                vaultKeyHolder.wipe(); sessionHolder.clear(); workspaceCache.clear()
            }

            override fun onResume(owner: LifecycleOwner) {
                if (idleLocker.isExpired()) { vaultKeyHolder.wipe(); sessionHolder.clear(); workspaceCache.clear() } else idleLocker.touch()
            }
        })

        setContent {
            LedgerlineTheme {
                val lockTitle = stringResource(R.string.lock_title)
                val lockSubtitle = stringResource(R.string.lock_subtitle)
                val link by pairLink.collectAsState()
                AppNav(
                    authGate = {
                        idleLocker.touch()
                        appLock.authenticate(this@MainActivity, lockTitle, lockSubtitle) is LockResult.Success
                    },
                    initialPairLink = link,
                )
            }
        }
    }

    // singleTask: a pairing link delivered while the activity is alive comes here.
    // Re-publishing it re-fires PairingScreen's LaunchedEffect (and routes to it).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractPairLink(intent)?.let { pairLink.value = it }
    }

    /** Extracts a validated `ledgerline://pair` deep link from an intent, or null. */
    private fun extractPairLink(intent: Intent?): String? =
        intent?.data
            ?.takeIf { it.scheme == "ledgerline" && it.host == "pair" }
            ?.toString()
}
