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
    private val prefsSink: de.ledgerline.app.core.prefs.DisplayPrefsSink,
    private val moduleAccess: de.ledgerline.app.core.ModuleAccess,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder, authEventBus: AuthEventBus, prefsSink: de.ledgerline.app.core.prefs.DisplayPrefsSink, moduleAccess: de.ledgerline.app.core.ModuleAccess) : this(
        sessionHolder,
        authEventBus,
        prefsSink,
        moduleAccess,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    /** Mirror the server's display preferences into local settings (source of truth = server). */
    private suspend fun adoptPrefs(dto: de.ledgerline.app.data.remote.dto.DisplayPrefsDto?) {
        dto ?: return
        prefsSink.setDisplayPrefs(
            de.ledgerline.app.core.prefs.DisplayPrefs(
                distance = dto.distance ?: "km",
                elevation = dto.elevation ?: "m",
                weight = dto.weight ?: "kg",
                temp = dto.temp ?: "c",
                glucose = dto.glucose ?: "mgdl",
                timeFormat = dto.timeFormat ?: "24h",
            ),
        )
    }

    /** Push updated display preferences to the server, then persist locally (optimistic). */
    suspend fun pushPreferences(prefs: de.ledgerline.app.core.prefs.DisplayPrefs): Boolean {
        prefsSink.setDisplayPrefs(prefs)
        val session = sessionHolder.get() ?: return false
        return try {
            apiProvider(session).putPreferences(
                de.ledgerline.app.data.remote.dto.DisplayPrefsDto(
                    distance = prefs.distance, elevation = prefs.elevation, weight = prefs.weight,
                    temp = prefs.temp, glucose = prefs.glucose, timeFormat = prefs.timeFormat,
                ),
            ).isSuccessful
        } catch (_: Exception) {
            false
        }
    }

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
            adoptPrefs(body?.user?.preferences)
            moduleAccess.set(body?.user?.modules)
            body?.user
        } catch (_: Exception) {
            null
        }
    }

    /** Account name + account-wide storage: [usedBytes] = files+gallery, [quotaBytes] = combined limit (null = unlimited). */
    data class AccountSnapshot(val name: String?, val usedBytes: Long, val quotaBytes: Long?)

    /**
     * One `/me` fetch yielding both the display name and the **combined** (files + gallery) storage
     * figures the server started exposing as `usage.quota` (web `7b2ad183`). `quotaBytes` is null
     * when unlimited. Also honours the remote wipe flag. Null on no session / network error.
     */
    suspend fun snapshot(): AccountSnapshot? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).me()
            if (!res.isSuccessful) return null
            val body = res.body() ?: return null
            if (body.wipe) authEventBus.emitWipe()
            adoptPrefs(body.user.preferences)
            moduleAccess.set(body.user.modules)
            val u = body.usage
            AccountSnapshot(
                name = body.user.name,
                usedBytes = (u?.files ?: 0L) + (u?.gallery ?: 0L),
                quotaBytes = u?.quota,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** The signed-in user's avatar image bytes (non-secret, `GET /avatar`), or null if none/failure. */
    suspend fun avatar(): ByteArray? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).avatar()
            if (res.isSuccessful) res.body()?.bytes() else null
        } catch (_: Exception) { null }
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
