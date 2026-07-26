package de.ledgerline.app.data

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Identity
import de.ledgerline.app.core.crypto.IdentityCrypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures the device has a published cross-user sharing identity (`/vaults/keys`).
 *
 * On [ensure]: fetch the caller's identity. If already published, unwrap the secrets
 * under the Vault Key and hold them in memory. If not, generate a fresh identity,
 * publish the public halves + VK-sealed secrets (write-once), and hold it. The secret
 * keys never touch disk (in-memory only, cleared on lock/logout). This is the
 * foundation the sharing/rotation flows (§14 R-S3) build on.
 */
@Singleton
class IdentityRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val identityCrypto: IdentityCrypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        identityCrypto: IdentityCrypto,
    ) : this(
        sessionHolder, vaultKeyHolder, identityCrypto,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    @Volatile
    private var current: Identity? = null

    /** The in-memory identity if [ensure] has succeeded this session, else null. */
    fun cached(): Identity? = current

    /**
     * Ensure a published identity exists and its secrets are loaded in memory. Returns
     * the [Identity] or null on any failure (offline, no VK, decrypt/publish failure) —
     * best-effort, never throws.
     */
    suspend fun ensure(): Identity? {
        current?.let { return it }
        val session = sessionHolder.get() ?: return null
        val vk = vaultKeyHolder.get() ?: return null
        val api = apiProvider(session)
        return try {
            val res = api.vaultKeys()
            if (!res.isSuccessful) return null
            val body = res.body() ?: return null
            val id = if (body.public_key != null) {
                identityCrypto.unwrap(body, vk) ?: return null
            } else {
                val fresh = identityCrypto.generate()
                val pub = api.putVaultKeys(identityCrypto.publishBody(fresh, vk))
                if (!pub.isSuccessful) return null
                fresh
            }
            current = id
            id
        } catch (_: Exception) {
            null
        }
    }

    /** Drop the in-memory identity (on lock / logout). */
    fun clear() {
        current = null
    }
}
