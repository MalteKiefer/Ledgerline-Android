package de.ledgerline.app.data

import de.ledgerline.app.core.AuthEventBus
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.MeUser
import de.ledgerline.app.domain.model.Session
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches the signed-in account identity from `GET /api/v1/me`. */
@Singleton
class AccountRepository(
    private val sessionHolder: SessionHolder,
    private val authEventBus: AuthEventBus,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder, authEventBus: AuthEventBus) : this(
        sessionHolder,
        authEventBus,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    /**
     * Current account (name/email/groups). Null on no session, network error, or failure.
     * Also carries the remote kill switch: when the response's `wipe` flag is set, fire the
     * wipe event so the app erases all local state and re-pairs.
     */
    suspend fun me(): MeUser? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).me()
            if (!res.isSuccessful) return null
            val body = res.body()
            if (body?.wipe == true) authEventBus.emitWipe()
            body?.user
        } catch (_: Exception) {
            null
        }
    }

    /** The owner's paired devices, current device first. Empty on no session/failure. */
    suspend fun devices(): List<de.ledgerline.app.data.remote.dto.DeviceDto> {
        val session = sessionHolder.get() ?: return emptyList()
        return try {
            val res = apiProvider(session).devices()
            if (res.isSuccessful) res.body()?.devices.orEmpty() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Revoke a device's token (it can no longer sync). Returns true on success. */
    suspend fun revokeDevice(id: Long): Boolean = call { it.revokeDevice(id.toString()) }

    /** Flag a device to erase its local state on next contact (remote kill switch). */
    suspend fun wipeDevice(id: Long): Boolean = call { it.wipeDevice(id.toString()) }

    private suspend fun call(block: suspend (LedgerlineApi) -> retrofit2.Response<Unit>): Boolean {
        val session = sessionHolder.get() ?: return false
        return try {
            block(apiProvider(session)).isSuccessful
        } catch (_: Exception) {
            false
        }
    }
}
