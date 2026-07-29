package de.ledgerline.app.core.security

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.WorkspaceCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single primitive for locking the vault. Extracted from the two identical inline
 * wipes in `MainActivity`'s lifecycle observer (background / idle-expiry).
 */
@Singleton
class VaultLocker @Inject constructor(
    private val vaultKeyHolder: VaultKeyHolder,
    private val sessionHolder: SessionHolder,
    private val workspaceCache: WorkspaceCache,
    private val galleryCache: GalleryCache,
    private val thumbCache: ThumbCache,
    private val metaCache: MetaCache,
    private val identityRepository: de.ledgerline.app.data.IdentityRepository,
    private val sharedVaultRepository: de.ledgerline.app.data.SharedVaultRepository,
    private val passwordsCache: de.ledgerline.app.core.PasswordsCache,
    private val exploreCache: de.ledgerline.app.core.ExploreCache,
    private val healthCache: de.ledgerline.app.core.HealthCache,
    private val financeCache: de.ledgerline.app.core.FinanceCache,
) {
    /**
     * Lock the vault: wipe the Vault Key + all in-memory decrypted state (incl. the
     * in-memory sharing-identity secrets). Does NOT touch the persisted session or the
     * keystore key — that is logout (ForceLogout).
     */
    fun lock() {
        vaultKeyHolder.wipe(); sessionHolder.clear(); workspaceCache.clear()
        galleryCache.clear(); thumbCache.clear(); metaCache.clear()
        identityRepository.clear()
        sharedVaultRepository.clear()
        passwordsCache.clear()
        exploreCache.clear()
        healthCache.clear()
        financeCache.clear()
    }
}
