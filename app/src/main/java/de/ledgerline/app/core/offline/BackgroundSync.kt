package de.ledgerline.app.core.offline

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.PasswordsRepository
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.WorkspaceRepository
import de.ledgerline.app.di.ApplicationScope
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the offline cache fresh in the background, within the zero-knowledge model.
 *
 * The Sanctum token is sealed behind a biometric-gated Keystore key, so a truly
 * app-*closed* WorkManager job can never authenticate — that would need the user present.
 * What we CAN do while the process is alive:
 *  - **Unlocked** (VK in memory): a full [LoadWorkspace] refresh (decrypts, updates the UI
 *    cache + disk cache) plus a policy-driven blob [Prefetcher] pass.
 *  - **Locked but alive** (VK wiped, session token still held): a token-only refresh of the
 *    sealed `/store` ciphertext into the disk cache ([WorkspaceRepository.refreshStoreCache]).
 *    The ciphertext is opaque, so nothing sensitive is exposed without the VK.
 *
 * Runs on a periodic tick; a killed process simply stops syncing (nothing persisted).
 */
@Singleton
class BackgroundSync @Inject constructor(
    private val load: LoadWorkspace,
    private val workspaceRepo: WorkspaceRepository,
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val offlineFlags: OfflineFlags,
    private val connectivity: Connectivity,
    private val prefetcher: Prefetcher,
    private val accountRepository: AccountRepository,
    private val passwordsRepo: PasswordsRepository,
    private val settingsStore: SettingsStore,
    private val offlineMapStore: de.ledgerline.app.core.map.OfflineMapStore,
    private val syncEngine: OfflineSyncEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile
    private var started = false

    /** Start the periodic sync loop. Idempotent — safe to call once from the Application. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (true) {
                val seconds = runCatching { settingsStore.backgroundRefreshSeconds.first() }
                    .getOrDefault(SettingsStore.DEFAULT_BACKGROUND_REFRESH_SECONDS)
                if (seconds <= 0) {
                    // Auto-refresh off: still drain any pending offline write outbox (self-gates on
                    // locked/offline/empty) so a queued edit isn't stranded forever, then idle-poll
                    // so a settings change is picked up promptly.
                    runCatching { syncEngine.syncNow() }
                    delay(OFF_POLL_MS)
                    continue
                }
                runCatching { syncOnce() }
                delay(seconds * 1000L)
            }
        }
    }

    private suspend fun syncOnce() {
        if (sessionHolder.get() == null) return
        if (!connectivity.isOnline()) return
        // Remote kill switch first, ungated by the offline setting — me() fires the wipe
        // event on wipe:true. Works even while locked (token only, no VK needed).
        accountRepository.me()
        // Report sync activity (also delivers the wipe flag) so the web devices list shows this
        // client as syncing — a heartbeat this tick means we're actively refreshing.
        accountRepository.heartbeat("syncing")
        // Drain the offline write outbox FIRST, ungated by the offline-cache master switch: an edit
        // can be queued on a server error even when caching is off, and it must still replay. syncNow
        // self-gates on locked/offline/empty, so this is a cheap no-op when there's nothing to push.
        runCatching { syncEngine.syncNow() }
        // Periodically (≤ every 12 h) check installed offline maps for newer server versions.
        val now = System.currentTimeMillis()
        if (now - lastMapCheck > 12L * 3600_000L) {
            lastMapCheck = now
            runCatching { offlineMapStore.checkUpdates() }
        }
        if (!offlineFlags.enabled()) return
        if (vaultKeyHolder.get() != null) {
            load.invoke()
            passwordsRepo.load()
            prefetcher.maybePrefetchOnUnlock()
        } else {
            workspaceRepo.refreshStoreCache()
            passwordsRepo.refreshStoreCache()
        }
    }

    private var lastMapCheck = 0L

    private companion object {
        const val INITIAL_DELAY_MS = 20_000L
        const val OFF_POLL_MS = 60_000L
    }
}
