package de.ledgerline.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.core.AppLockState
import de.ledgerline.app.core.security.VaultAuthorizers
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.ui.nav.AppNav
import de.ledgerline.app.ui.theme.LedgerlineTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity (FragmentActivity is required by BiometricPrompt). The app locks — the in-memory
 * session is cleared and the biometric lock screen re-shown — whenever the app is backgrounded
 * (ON_STOP) and after an idle timeout. No vault key exists in the plaintext-relational pivot; locking
 * simply drops the session token from memory (it stays Keystore-sealed on disk).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLockState: AppLockState
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var deepLinkBus: de.ledgerline.app.core.DeepLinkBus
    @Inject lateinit var pushRegistrar: de.ledgerline.app.push.PushRegistrar
    @Inject lateinit var shareInbox: de.ledgerline.app.core.ShareInbox
    @Inject lateinit var galleryBackup: de.ledgerline.app.data.gallery.GalleryBackup

    private var idleTimeoutMs = 5 * 60_000L
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()

    // Keep-screen-on state (display-only). Re-armed on each interaction; a finite duration clears it.
    private var keepScreenOn = false
    private var keepScreenOnMinutes = SettingsStore.DEFAULT_KEEP_SCREEN_ON_MINUTES
    private var keepScreenReleaseJob: Job? = null

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

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = SystemClock.elapsedRealtime()
        if (keepScreenOn && keepScreenOnMinutes > 0) armKeepScreen()
    }

    /** Route a tapped push notification to the notification centre once the shell is composed. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_NOTIFICATIONS) {
            deepLinkBus.emit(de.ledgerline.app.core.DeepLink.NOTIFICATIONS)
        }
        handleShare(intent)
    }

    /**
     * ACTION_SEND(_MULTIPLE): copy the shared URIs into cache NOW (the read grant is only valid while
     * this activity holds the intent), then publish them to [shareInbox]; the unlocked shell offers the
     * upload. Copying runs off the main thread but within the live grant.
     */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<android.net.Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> return
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                uris.mapNotNull { uri ->
                    runCatching {
                        val name = queryDisplayName(uri) ?: "shared_${System.nanoTime()}"
                        val dest = java.io.File(dir, "${System.nanoTime()}_$name")
                        contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { i.copyTo(it) } }
                        de.ledgerline.app.core.ShareInbox.Item(dest, name, contentResolver.getType(uri))
                    }.getOrNull()
                }
            }
            if (items.isNotEmpty()) shareInbox.set(items)
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        de.ledgerline.app.push.PushNotifier.ensureChannels(this)
        // Deliver any push endpoint that arrived while locked, once we're unlocked again.
        lifecycleScope.launch {
            appLockState.unlocked.collect { unlocked ->
                if (unlocked) { pushRegistrar.flushPending(); galleryBackup.runIfEnabled() }
            }
        }
        if (!de.ledgerline.app.BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val barStyle = if (isDark) {
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)

        lifecycleScope.launch { idleTimeoutMs = settingsStore.timeoutMinutes.first() * 60_000L }

        lifecycleScope.launch {
            settingsStore.keepScreenOn
                .combine(settingsStore.keepScreenOnMinutes) { on, min -> on to min }
                .collect { (on, min) ->
                    keepScreenOn = on
                    keepScreenOnMinutes = min
                    armKeepScreen()
                }
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Backgrounding locks: drop the session from memory; re-biometric to return.
                appLockState.lock()
            }

            override fun onResume(owner: LifecycleOwner) {
                if (SystemClock.elapsedRealtime() - lastInteraction > idleTimeoutMs) appLockState.lock()
                lastInteraction = SystemClock.elapsedRealtime()
                armKeepScreen()
            }
        })

        setContent {
            val themeMode by settingsStore.themeMode.collectAsState(initial = de.ledgerline.app.data.ThemeMode.SYSTEM)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (themeMode) {
                de.ledgerline.app.data.ThemeMode.LIGHT -> false
                de.ledgerline.app.data.ThemeMode.DARK -> true
                de.ledgerline.app.data.ThemeMode.SYSTEM -> systemDark
            }
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !dark
            }
            LedgerlineTheme(darkTheme = dark) {
                val auth = VaultAuthorizers(
                    activity = this@MainActivity,
                    lockTitle = stringResource(R.string.lock_title),
                    lockSubtitle = stringResource(R.string.lock_subtitle),
                    cancelText = stringResource(R.string.action_cancel),
                )
                AppNav(authorize = auth.authorize)
            }
        }
    }

    companion object {
        /** Intent extra set by a push notification's tap PendingIntent. */
        const val EXTRA_OPEN = "ledgerline.open"
        const val OPEN_NOTIFICATIONS = "notifications"
    }
}
