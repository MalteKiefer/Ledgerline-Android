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
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.VaultAuthorizers
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.core.security.VaultLocker
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.ui.nav.AppNav
import de.ledgerline.app.ui.theme.LedgerlineTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    @Inject lateinit var operationManager: OperationManager

    // Keep-screen-on state (display-only; independent of the idle auto-lock). The flag
    // is (re)armed on each user interaction; if a finite duration is set, a timer clears
    // it after that many minutes of inactivity so the screen can finally sleep.
    private var keepScreenOn = false
    private var keepScreenOnMinutes = SettingsStore.DEFAULT_KEEP_SCREEN_ON_MINUTES
    private var keepScreenReleaseJob: Job? = null

    /** Apply FLAG_KEEP_SCREEN_ON per the current setting, arming the release timer. */
    private fun armKeepScreen() {
        keepScreenReleaseJob?.cancel()
        keepScreenReleaseJob = null
        if (!keepScreenOn) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (keepScreenOnMinutes > 0) {
            keepScreenReleaseJob = lifecycleScope.launch {
                delay(keepScreenOnMinutes * 60_000L)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Reset the keep-awake timer on any touch/key so "keep awake for N minutes" counts
    // from the last interaction, not from when the screen was opened.
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (keepScreenOn && keepScreenOnMinutes > 0) armKeepScreen()
    }

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

        // Apply the persisted idle-lock timeout asynchronously (never block the main
        // thread on DataStore I/O). Until it loads, IdleLocker's safe default applies, and
        // no unlock can complete before onCreate returns anyway.
        lifecycleScope.launch {
            idleLocker.setTimeoutMs(settingsStore.timeoutMinutes.first() * 60_000L)
        }

        // Track the keep-screen-on preference and (re)apply the window flag on change.
        lifecycleScope.launch {
            settingsStore.keepScreenOn
                .combine(settingsStore.keepScreenOnMinutes) { on, min -> on to min }
                .collect { (on, min) ->
                    keepScreenOn = on
                    keepScreenOnMinutes = min
                    armKeepScreen()
                }
        }

        // Cold-start: accept a validated pairing deep link from the launch intent.
        pairLink.value = extractPairLink(intent)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Skip exactly one auto-lock when WE launched a system picker (SAF)
                // or credential prompt, which briefly backgrounds us. A real
                // background (home button) has no armed skip.
                if (lockGuard.consumeSkip()) return

                // Defer the wipe while a background-enabled op is running: the op keeps
                // the VK alive (behind the foreground-service notification) and the
                // OperationManager wipes via VaultLocker when the last op drains.
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
                // Re-arm keep-screen-on: a release timer may have cleared the flag while
                // the app was away, and returning should honor the setting again.
                armKeepScreen()
                // Defensive: if a picker returned via a dialog path without onStop,
                // don't leave a stale skip armed for the next real background.
                lockGuard.clear()
            }
        })

        setContent {
            LedgerlineTheme {
                val link by pairLink.collectAsState()
                // One shared factory for both CryptoObject-bound biometric authorizers.
                val auth = VaultAuthorizers(
                    activity = this@MainActivity,
                    idleLocker = idleLocker,
                    lockTitle = stringResource(R.string.lock_title),
                    lockSubtitle = stringResource(R.string.lock_subtitle),
                    rememberSubtitle = stringResource(R.string.lock_remember_subtitle),
                    cancelText = stringResource(R.string.action_cancel),
                )
                AppNav(
                    authorize = auth.authorize,
                    strongAuthorize = auth.strongAuthorize,
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
