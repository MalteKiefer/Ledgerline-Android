package de.ledgerline.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.core.security.AppLock
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.LockResult
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.ui.nav.AppNav
import de.ledgerline.app.ui.theme.LedgerlineTheme
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
    private val appLock = AppLock()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MASVS-STORAGE: block screenshots, screen recording, recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        // Only accept a validated pairing deep link.
        val pairLink = intent?.data
            ?.takeIf { it.scheme == "ledgerline" && it.host == "pair" }
            ?.toString()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                vaultKeyHolder.wipe() // background → drop the Vault Key
            }

            override fun onResume(owner: LifecycleOwner) {
                if (idleLocker.isExpired()) vaultKeyHolder.wipe() else idleLocker.touch()
            }
        })

        setContent {
            LedgerlineTheme {
                val lockTitle = stringResource(R.string.lock_title)
                val lockSubtitle = stringResource(R.string.lock_subtitle)
                AppNav(
                    authGate = {
                        idleLocker.touch()
                        appLock.authenticate(this@MainActivity, lockTitle, lockSubtitle) is LockResult.Success
                    },
                    initialPairLink = pairLink,
                )
            }
        }
    }
}
