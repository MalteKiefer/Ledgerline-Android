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
    }

    private fun gateway(configured: Boolean = true) = object : VaultGateway {
        override suspend fun fetch() = if (configured)
            VaultParams(configured = true, salt = "s", kdfOps = 2, kdfMem = 1, wrappedVk = "w", wrapNonce = "n",
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
}
