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
import androidx.biometric.BiometricPrompt
import de.ledgerline.app.core.security.AppLock
import de.ledgerline.app.core.security.CryptoAuth
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.core.security.VaultLocker
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

    @Inject lateinit var idleLocker: IdleLocker
    @Inject lateinit var locker: VaultLocker
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var lockGuard: LockGuard
    private val appLock = AppLock()

    // Emits the latest validated pairing deep link. singleTask means a link
    // delivered while running arrives via onNewIntent, not a fresh onCreate.
    private val pairLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MASVS-STORAGE: block screenshots, screen recording, recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // The app is always dark; force light (dark-style) system bar icons so the
        // clock/notification icons stay visible on the transparent dark bars.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // Apply the persisted idle-lock timeout before any unlock can happen.
        idleLocker.timeoutMs = runBlocking { settingsStore.timeoutMinutes.first() } * 60_000L

        // Cold-start: accept a validated pairing deep link from the launch intent.
        pairLink.value = extractPairLink(intent)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Skip exactly one auto-lock when WE launched a system picker (SAF)
                // or credential prompt, which briefly backgrounds us. A real
                // background (home button) has no armed skip → wipe normally.
                if (!lockGuard.consumeSkip()) {
                    locker.lock()
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                if (idleLocker.isExpired()) {
                    locker.lock()
                } else idleLocker.touch()
                // Defensive: if a picker returned via a dialog path without onStop,
                // don't leave a stale skip armed for the next real background.
                lockGuard.clear()
            }
        })

        setContent {
            LedgerlineTheme {
                val lockTitle = stringResource(R.string.lock_title)
                val lockSubtitle = stringResource(R.string.lock_subtitle)
                val link by pairLink.collectAsState()
                // Runs ONE CryptoObject-bound biometric on the keystore cipher and
                // returns the authorised cipher (or null on cancel/failure). Threaded
                // into SessionStore.save/load via the screens.
                val authorize: suspend (javax.crypto.Cipher) -> javax.crypto.Cipher? = { cipher ->
                    idleLocker.touch()
                    when (
                        val r = appLock.authenticate(
                            this@MainActivity, lockTitle, lockSubtitle, BiometricPrompt.CryptoObject(cipher),
                        )
                    ) {
                        is CryptoAuth.Success -> r.cipher
                        else -> null
                    }
                }
                AppNav(
                    authorize = authorize,
                    initialPairLink = link,
                )
            }
        }

        // Belt-and-suspenders: force light status bar icons for the dark UI.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
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
