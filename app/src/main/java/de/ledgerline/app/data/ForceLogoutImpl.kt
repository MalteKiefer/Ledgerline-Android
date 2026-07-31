package de.ledgerline.app.data

import de.ledgerline.app.core.AppLockState
import de.ledgerline.app.core.AvatarCache
import de.ledgerline.app.core.AccountSnapshotCache
import de.ledgerline.app.core.ModuleAccess
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.data.finance.FinanceRepository
import de.ledgerline.app.domain.usecase.ForceLogout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full local wipe on a revoked token (authenticated 401) or a remote-wipe kill switch. Order: clear
 * in-memory state + cached finance data first, then delete the persisted sealed session and the
 * auth-gated Keystore key last — deleting the key makes any remaining sealed blob undecryptable, so
 * a fresh pairing is the only way back in. (Plaintext-relational pivot — no vault key to wipe.)
 */
@Singleton
class ForceLogoutImpl @Inject constructor(
    private val sessionStore: SessionStore,
    private val keystoreSealer: KeystoreSealer,
    private val sessionHolder: SessionHolder,
    private val appLockState: AppLockState,
    private val financeRepository: FinanceRepository,
    private val avatarCache: AvatarCache,
    private val snapshotCache: AccountSnapshotCache,
    private val moduleAccess: ModuleAccess,
) : ForceLogout {
    override suspend fun invoke() {
        appLockState.lock()
        sessionHolder.clear()
        financeRepository.clear()
        avatarCache.put(null)
        snapshotCache.put(null)
        moduleAccess.clear()
        sessionStore.clear()
        keystoreSealer.clear()
    }
}
