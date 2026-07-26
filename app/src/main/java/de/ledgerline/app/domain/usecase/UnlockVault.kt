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

    private companion object {
        // Sanity bounds for server-supplied Argon2id cost. The server uses OPSLIMIT_SENSITIVE
        // (~4) + MEMLIMIT_MODERATE (256 MiB); reject values weak enough to make the wrapped-VK
        // brute-forceable, or high enough to OOM the device. A compromised/MITM server that
        // serves ops≈0 must NOT be able to weaken the KDF silently.
        const val MIN_OPS = 2L
        const val MAX_OPS = 100L
        const val MIN_MEM = 8L * 1024 * 1024          // 8 MiB
        const val MAX_MEM = 2L * 1024 * 1024 * 1024   // 2 GiB
    }

    suspend fun withPassphrase(gateway: VaultGateway, passphrase: ByteArray): Outcome<Unit> {
        val v = try { gateway.fetch() } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
        if (!v.configured) return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        // A configured vault must carry these; a null here is a malformed server response,
        // not a wrong passphrase — surface it explicitly rather than as a swallowed NPE.
        val salt = v.salt; val ops = v.kdfOps; val mem = v.kdfMem
        val wrapped = v.wrappedVk; val nonce = v.wrapNonce
        if (salt == null || ops == null || mem == null || wrapped == null || nonce == null) {
            passphrase.fill(0)
            return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        }
        // Refuse implausible KDF cost rather than derive a weak/DoS KEK (M1).
        if (ops !in MIN_OPS..MAX_OPS || mem !in MIN_MEM..MAX_MEM) {
            passphrase.fill(0)
            return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        }
        var kek: ByteArray? = null
        return try {
            kek = crypto.deriveKek(passphrase, crypto.b64decode(salt), ops, mem)
            val vk = crypto.secretBoxOpen(crypto.b64decode(wrapped), crypto.b64decode(nonce), kek)
                ?: return Outcome.Err(ErrorKind.WRONG_PASSPHRASE)
            holder.set(vk)
            Outcome.Ok(Unit)
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
        finally { kek?.fill(0); passphrase.fill(0) } // wipe KEK on every path (M1)
    }

    suspend fun withRecoveryCode(gateway: VaultGateway, hexCode: String): Outcome<Unit> {
        val v = try { gateway.fetch() } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
        if (!v.configured || !v.hasRecovery) return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        val wrapped = v.wrappedVkRecovery ?: return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        val nonce = v.recoveryNonce ?: return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        var recoveryBytes: ByteArray? = null
        var recoveryKey: ByteArray? = null
        return try {
            recoveryBytes = crypto.fromHex(hexCode)
            recoveryKey = crypto.genericHash32(recoveryBytes)
            val vk = crypto.secretBoxOpen(crypto.b64decode(wrapped), crypto.b64decode(nonce), recoveryKey)
                ?: return Outcome.Err(ErrorKind.WRONG_PASSPHRASE)
            holder.set(vk)
            Outcome.Ok(Unit)
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
        finally { recoveryBytes?.fill(0); recoveryKey?.fill(0) }
    }
}
