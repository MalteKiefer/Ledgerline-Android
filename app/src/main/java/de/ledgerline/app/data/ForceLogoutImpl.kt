package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.backup.BackupStateStore
import de.ledgerline.app.domain.usecase.ForceLogout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full wipe on an authenticated 401 (revoked token). Order matters: clear all
 * in-memory secrets and decrypted caches first, then delete the persisted sealed
 * session and the auth-gated keystore key last — deleting the keystore key makes any
 * remaining sealed blob undecryptable, so a fresh pairing is the only way back in.
 */
@Singleton
class ForceLogoutImpl @Inject constructor(
    private val sessionStore: SessionStore,
    private val keystoreSealer: KeystoreSealer,
    private val vaultKeyHolder: VaultKeyHolder,
    private val sessionHolder: SessionHolder,
    private val workspaceCache: WorkspaceCache,
    private val galleryCache: GalleryCache,
    private val thumbCache: ThumbCache,
    private val metaCache: MetaCache,
    private val storeCache: StoreDiskCache,
    private val blobCache: BlobDiskCache,
    private val backupStateStore: BackupStateStore,
    private val rememberedVault: RememberedVaultStore,
    private val placeRepository: PlaceRepository,
    private val securityLog: de.ledgerline.app.core.security.SecurityLog,
    private val duressGuard: de.ledgerline.app.core.security.DuressGuard,
    private val clockGuard: de.ledgerline.app.core.security.ClockRollbackGuard,
    private val identityRepository: IdentityRepository,
    private val sharedVaultRepository: SharedVaultRepository,
    private val avatarCache: de.ledgerline.app.core.AvatarCache,
    private val snapshotCache: de.ledgerline.app.core.AccountSnapshotCache,
    private val passwordsCache: de.ledgerline.app.core.PasswordsCache,
    private val exploreCache: de.ledgerline.app.core.ExploreCache,
    private val healthCache: de.ledgerline.app.core.HealthCache,
    private val financeCache: de.ledgerline.app.core.FinanceCache,
    private val moduleAccess: de.ledgerline.app.core.ModuleAccess,
) : ForceLogout {
    override suspend fun invoke() {
        // In-memory first (secrets + decrypted caches).
        vaultKeyHolder.wipe()
        sessionHolder.clear()
        identityRepository.clear()
        sharedVaultRepository.clear()
        avatarCache.put(null)
        snapshotCache.put(null)
        passwordsCache.clear()
        exploreCache.clear()
        healthCache.clear()
        financeCache.clear()
        moduleAccess.clear()
        workspaceCache.clear()
        galleryCache.clear()
        thumbCache.clear()
        metaCache.clear()
        // Persisted last: drop the sealed session, the offline ciphertext caches, the
        // backup bookkeeping, and delete the keystore key so a re-pair is required. (A
        // normal lock keeps the disk cache — only this forced-logout path wipes it, §11.)
        storeCache.clear()
        blobCache.clear()
        backupStateStore.clear()
        rememberedVault.clear()
        placeRepository.clear()
        // Security state: reset the duress counter and erase the audit log so a wiped
        // device is left clean (the wipe reason is moot once the device is unpaired).
        duressGuard.reset()
        clockGuard.reset()
        securityLog.clear()
        sessionStore.clear()
        keystoreSealer.clear()
    }
}
