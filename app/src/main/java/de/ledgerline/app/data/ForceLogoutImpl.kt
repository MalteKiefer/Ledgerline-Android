package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.core.security.VaultKeyHolder
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
) : ForceLogout {
    override suspend fun invoke() {
        // In-memory first (secrets + decrypted caches).
        vaultKeyHolder.wipe()
        sessionHolder.clear()
        workspaceCache.clear()
        galleryCache.clear()
        thumbCache.clear()
        metaCache.clear()
        // Persisted last: drop the sealed session and delete the keystore key so a
        // re-pair is required.
        sessionStore.clear()
        keystoreSealer.clear()
    }
}
