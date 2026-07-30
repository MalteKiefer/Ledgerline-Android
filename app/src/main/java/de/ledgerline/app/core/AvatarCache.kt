package de.ledgerline.app.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny on-disk cache for the (non-secret) IdP avatar image so Settings shows it instantly and works
 * offline — the bytes are fetched once via `GET /avatar` and only re-written when they change. Not
 * ZK-sensitive (the avatar is served in the clear like on the web), so plain bytes in cacheDir are fine.
 */
@Singleton
class AvatarCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val file: File get() = File(context.cacheDir, "avatar.img")

    /** Cached avatar bytes for an immediate first paint, or null if none cached. */
    fun get(): ByteArray? = runCatching { file.takeIf { it.exists() && it.length() > 0 }?.readBytes() }.getOrNull()

    /** Persist fresh bytes (write-through after a network fetch); null clears the cache. */
    fun put(bytes: ByteArray?) {
        runCatching { if (bytes == null || bytes.isEmpty()) file.delete() else file.writeBytes(bytes) }
    }
}
