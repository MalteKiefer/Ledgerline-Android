package de.ledgerline.app.domain.usecase

/**
 * Forces a full logout: wipes the in-memory Vault Key, session and all decrypted
 * caches, then deletes the persisted sealed session and the auth-gated keystore key
 * so a re-pair is required. Triggered by an authenticated 401 (revoked token).
 */
interface ForceLogout {
    suspend fun invoke()
}
