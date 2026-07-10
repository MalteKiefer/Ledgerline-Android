package de.ledgerline.app.data

import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.VaultGateway
import de.ledgerline.app.domain.usecase.VaultParams

/** Fetches vault KDF params over the pinned, authenticated session. */
class VaultRepository(private val session: Session) : VaultGateway {
    private val api = NetworkFactory.create(session.baseUrl, tokenProvider = { session.token }, pin = session.spkiPin)

    override suspend fun fetch(): VaultParams {
        val res = api.vault()
        val b = res.body() ?: return VaultParams(configured = false)
        return VaultParams(
            configured = b.configured,
            salt = b.salt, kdfOps = b.kdf_ops, kdfMem = b.kdf_mem,
            wrappedVk = b.wrapped_vault_key, wrapNonce = b.wrap_nonce,
            hasRecovery = b.has_recovery,
            wrappedVkRecovery = b.wrapped_vault_key_recovery, recoveryNonce = b.recovery_nonce,
        )
    }
}
