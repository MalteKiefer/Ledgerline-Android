package de.ledgerline.app.data

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
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder) : this(
        sessionHolder,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    /** Current account (name/email/groups). Null on no session, network error, or failure. */
    suspend fun me(): MeUser? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).me()
            if (!res.isSuccessful) return null
            res.body()?.user
        } catch (_: Exception) {
            null
        }
    }
}
