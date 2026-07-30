package de.ledgerline.app.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny on-disk cache for the account hub snapshot (display name + combined files/gallery usage +
 * quota) so the Home hub paints instantly and works offline — refreshed from `GET /me` in the
 * background. Not ZK-sensitive (the server holds these figures anyway); plain values in cacheDir.
 */
@Singleton
class AccountSnapshotCache @Inject constructor(@ApplicationContext private val context: Context) {
    data class Snap(val name: String?, val usedBytes: Long, val quotaBytes: Long?)

    private val file: File get() = File(context.cacheDir, "account_snapshot.txt")

    /** Last cached snapshot, or null if none / unreadable. Format: `used\tquota\tname` (quota "" = unlimited). */
    fun get(): Snap? = runCatching {
        val parts = file.takeIf { it.exists() }?.readText()?.split("\t", limit = 3) ?: return null
        if (parts.size < 3) return null
        Snap(parts[2].ifEmpty { null }, parts[0].toLongOrNull() ?: 0L, parts[1].ifEmpty { null }?.toLongOrNull())
    }.getOrNull()

    /** Persist a fresh snapshot (write-through after a `/me` fetch); null clears the cache. */
    fun put(snap: Snap?) {
        runCatching {
            if (snap == null) file.delete()
            else file.writeText("${snap.usedBytes}\t${snap.quotaBytes ?: ""}\t${snap.name ?: ""}")
        }
    }
}
