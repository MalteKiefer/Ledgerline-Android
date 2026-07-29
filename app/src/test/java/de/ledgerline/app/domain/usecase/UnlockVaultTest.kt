package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockVaultTest {
    // Fake crypto: KEK = passphrase bytes; secretBoxOpen returns a vk only when key == "rightKEK".
    private val fakeCrypto = object : Crypto {
        override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) = passphrase
        override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? =
            if (String(key) == "rightKEK") ByteArray(32) { 9 } else null
        override fun genericHash32(input: ByteArray) = ByteArray(32) { 1 }
        override fun b64decode(s: String) = s.toByteArray()
        override fun b64encode(b: ByteArray) = String(b)
        override fun fromHex(s: String) = s.toByteArray()
        override fun openManifest(ciphertext: String, vk: ByteArray): String? = null
        override fun sealManifest(json: String, vk: ByteArray): String = "SEALED:$json"
        override val contentChunkSize: Int = 1
        override fun u32le(n: Int) = ByteArray(4)
        override fun readU32le(bytes: ByteArray, off: Int) = 0
        override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor = throw NotImplementedError()
        override fun contentDecryptorFromKey(fileKey: ByteArray): de.ledgerline.app.core.crypto.Crypto.ContentDecryptor = throw NotImplementedError()
        override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = throw NotImplementedError()
    }

    private fun gateway(configured: Boolean = true) = object : VaultGateway {
        override suspend fun fetch() = if (configured)
            // In-range KDF cost (matches server OPSLIMIT_SENSITIVE / MEMLIMIT_MODERATE); the
            // fake deriveKek ignores the numbers but they must pass UnlockVault's sanity bounds.
            VaultParams(configured = true, salt = "s", kdfOps = 4, kdfMem = 268_435_456, wrappedVk = "w", wrapNonce = "n",
                hasRecovery = true, wrappedVkRecovery = "wr", recoveryNonce = "rn")
        else VaultParams(configured = false)
    }

    @Test fun wrong_passphrase_maps_to_error() = runTest {
        val vk = VaultKeyHolder()
        val res = UnlockVault(fakeCrypto, vk).withPassphrase(gateway(), "wrongKEK".toByteArray())
        assertTrue(res is Outcome.Err && res.kind == ErrorKind.WRONG_PASSPHRASE)
        assertEquals(false, vk.unlocked.value)
    }

    @Test fun correct_passphrase_sets_vk() = runTest {
        val vk = VaultKeyHolder()
        val res = UnlockVault(fakeCrypto, vk).withPassphrase(gateway(), "rightKEK".toByteArray())
        assertTrue(res is Outcome.Ok)
        assertEquals(true, vk.unlocked.value)
    }

    @Test fun not_configured_maps_to_error() = runTest {
        val vk = VaultKeyHolder()
        val res = UnlockVault(fakeCrypto, vk).withPassphrase(gateway(configured = false), "x".toByteArray())
        assertTrue(res is Outcome.Err && res.kind == ErrorKind.NOT_CONFIGURED)
    }

    @Test fun weak_kdf_params_are_rejected() = runTest {
        // A server serving near-zero Argon2id cost must not yield a (weak) unlock (M1).
        val weak = object : VaultGateway {
            override suspend fun fetch() = VaultParams(
                configured = true, salt = "s", kdfOps = 0, kdfMem = 1, wrappedVk = "w", wrapNonce = "n",
            )
        }
        val vk = VaultKeyHolder()
        val res = UnlockVault(fakeCrypto, vk).withPassphrase(weak, "rightKEK".toByteArray())
        assertTrue(res is Outcome.Err && res.kind == ErrorKind.NOT_CONFIGURED)
        assertEquals(false, vk.unlocked.value)
    }
}
