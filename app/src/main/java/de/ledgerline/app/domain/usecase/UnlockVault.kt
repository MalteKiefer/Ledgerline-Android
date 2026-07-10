package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder

data class VaultParams(
    val configured: Boolean,
    val salt: String? = null,
    val kdfOps: Long? = null,
    val kdfMem: Long? = null,
    val wrappedVk: String? = null,
    val wrapNonce: String? = null,
    val hasRecovery: Boolean = false,
    val wrappedVkRecovery: String? = null,
    val recoveryNonce: String? = null,
)

interface VaultGateway { suspend fun fetch(): VaultParams }

/** Derives the KEK (Argon2id) with server params and unwraps the Vault Key. */
class UnlockVault(private val crypto: Crypto, private val holder: VaultKeyHolder) {

    suspend fun withPassphrase(gateway: VaultGateway, passphrase: ByteArray): Outcome<Unit> {
        val v = try { gateway.fetch() } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
        if (!v.configured) return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        return try {
            val kek = crypto.deriveKek(passphrase, crypto.b64decode(v.salt!!), v.kdfOps!!, v.kdfMem!!)
            val vk = crypto.secretBoxOpen(crypto.b64decode(v.wrappedVk!!), crypto.b64decode(v.wrapNonce!!), kek)
                ?: return Outcome.Err(ErrorKind.WRONG_PASSPHRASE)
            kek.fill(0)
            holder.set(vk)
            Outcome.Ok(Unit)
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
        finally { passphrase.fill(0) }
    }

    suspend fun withRecoveryCode(gateway: VaultGateway, hexCode: String): Outcome<Unit> {
        val v = try { gateway.fetch() } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
        if (!v.configured || !v.hasRecovery) return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        return try {
            val recoveryKey = crypto.genericHash32(crypto.fromHex(hexCode))
            val vk = crypto.secretBoxOpen(crypto.b64decode(v.wrappedVkRecovery!!), crypto.b64decode(v.recoveryNonce!!), recoveryKey)
                ?: return Outcome.Err(ErrorKind.WRONG_PASSPHRASE)
            holder.set(vk)
            Outcome.Ok(Unit)
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
    }
}
