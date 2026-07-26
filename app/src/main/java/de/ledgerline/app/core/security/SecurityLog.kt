package de.ledgerline.app.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A security-relevant event type recorded in the [SecurityLog]. */
enum class SecurityEventType {
    /** Device coupling (QR pairing) completed. */
    PAIRED,
    UNLOCK_SUCCESS,
    UNLOCK_FAILED,
    RECOVERY_UNLOCK,
    /** Repeated failures triggered an escalating lockout (never destructive). */
    THROTTLE_LOCKOUT,
    /** The duress threshold was hit — everything local was wiped. */
    DURESS_WIPE,
    REMOTE_WIPE,
    LOGOUT,
    /** A client-integrity check flagged a non-hardware-attested key or a rooted/tampered device (informational, never blocking). */
    INTEGRITY_WARNING,
}

@Serializable
data class SecurityLogEntry(val at: Long, val type: String, val detail: String? = null)

/**
 * Encrypted, device-local security audit log. Records coupling/pairing, unlock
 * successes and failures, lockouts, and wipes so the owner can review security
 * activity in Settings.
 *
 * At rest the log is sealed with a hardware-backed AES-256-GCM key ([KeystoreSealer]
 * with `requireAuth=false`, so events can be recorded before the vault is unlocked —
 * e.g. a failed unlock). It holds no vault content, only metadata (event type,
 * timestamp, short detail). The log is a bounded ring ([MAX] newest entries) and is
 * cleared by any full wipe (remote kill switch, 401, or duress) so a wiped device is
 * left clean.
 */
@Singleton
class SecurityLog @Inject constructor(@ApplicationContext context: Context) {
    private val sealer = KeystoreSealer(alias = "ledgerline_seclog_key_v1", requireAuth = false)
    private val file = File(context.filesDir, "security_log.bin")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SecurityLogEntry.serializer())
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<SecurityLogEntry>>(emptyList())
    val entries: StateFlow<List<SecurityLogEntry>> = _entries
    private var loaded = false

    private companion object {
        const val MAX = 300
    }

    /** Populate [entries] from disk once (idempotent). Call before observing the log. */
    suspend fun ensureLoaded() = mutex.withLock {
        if (!loaded) {
            _entries.value = withContext(Dispatchers.IO) { readFromDisk() }.takeLast(MAX)
            loaded = true
        }
    }

    /** Append an event and persist. Cheap disk I/O runs off the main thread. */
    suspend fun record(type: SecurityEventType, detail: String? = null, at: Long = System.currentTimeMillis()) =
        mutex.withLock {
            if (!loaded) {
                _entries.value = withContext(Dispatchers.IO) { readFromDisk() }
                loaded = true
            }
            val next = (_entries.value + SecurityLogEntry(at, type.name, detail)).takeLast(MAX)
            _entries.value = next
            withContext(Dispatchers.IO) { writeToDisk(next) }
        }

    /** Erase the whole log (called by every full wipe). */
    suspend fun clear() = mutex.withLock {
        _entries.value = emptyList()
        loaded = true
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
        Unit
    }

    private fun readFromDisk(): List<SecurityLogEntry> = try {
        if (!file.exists()) emptyList()
        else json.decodeFromString(serializer, String(sealer.open(file.readBytes()), Charsets.UTF_8))
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeToDisk(list: List<SecurityLogEntry>) {
        try {
            val bytes = json.encodeToString(serializer, list).toByteArray(Charsets.UTF_8)
            val sealed = sealer.seal(bytes)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(file)) { file.writeBytes(sealed); tmp.delete() }
        } catch (_: Exception) {
            // Best-effort: a failed log write must never break the security flow it records.
        }
    }
}
