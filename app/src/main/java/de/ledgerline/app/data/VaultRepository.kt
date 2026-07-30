package de.ledgerline.app.data

import de.ledgerline.app.core.offline.CachedVaultParams
import de.ledgerline.app.core.offline.VaultParamsCache
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.VaultGateway
import de.ledgerline.app.domain.usecase.VaultParams

/**
 * Fetches vault KDF params over the pinned, authenticated session. **Offline-capable (§11):** a
 * successful online fetch is written through to [cache]; when the network is unreachable the last
 * cached params are returned so a passphrase (or recovery-code) unlock still works fully offline.
 * The cached blob is ZK-server-public (public KDF params + KEK-sealed wrapped VK) — see
 * [VaultParamsCache].
 */
class VaultRepository(
    private val session: Session,
    private val cache: VaultParamsCache? = null,
) : VaultGateway {
    private val api = NetworkFactory.create(session.baseUrl, tokenProvider = { session.token }, pin = session.spkiPin)

    override suspend fun fetch(): VaultParams {
        val b = try {
            api.vault().body()
        } catch (e: Exception) {
            // Offline: fall back to the cached params so unlock still works; rethrow if none cached.
            return cache?.get()?.toParams() ?: throw e
        }
        if (b == null) return VaultParams(configured = false)
        cache?.put(
            CachedVaultParams(
                configured = b.configured,
                salt = b.salt, kdfOps = b.kdf_ops, kdfMem = b.kdf_mem,
                wrappedVk = b.wrapped_vault_key, wrapNonce = b.wrap_nonce,
                hasRecovery = b.has_recovery,
                wrappedVkRecovery = b.wrapped_vault_key_recovery, recoveryNonce = b.recovery_nonce,
            ),
        )
        return VaultParams(
            configured = b.configured,
            salt = b.salt, kdfOps = b.kdf_ops, kdfMem = b.kdf_mem,
            wrappedVk = b.wrapped_vault_key, wrapNonce = b.wrap_nonce,
            hasRecovery = b.has_recovery,
            wrappedVkRecovery = b.wrapped_vault_key_recovery, recoveryNonce = b.recovery_nonce,
        )
    }

    private fun CachedVaultParams.toParams() = VaultParams(
        configured = configured,
        salt = salt, kdfOps = kdfOps, kdfMem = kdfMem,
        wrappedVk = wrappedVk, wrapNonce = wrapNonce,
        hasRecovery = hasRecovery,
        wrappedVkRecovery = wrappedVkRecovery, recoveryNonce = recoveryNonce,
    )
}
