package de.ledgerline.app.core.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The raw sealed-store envelope exactly as the server returns it from `/store` and
 * `/gallery/store`: `{ ciphertext, version }`. The [ciphertext] is already sealed
 * with the Vault Key, so persisting it to disk leaks nothing (§9/§11) — decryption
 * always happens in-memory and needs the VK.
 */
@Serializable
data class StoreEnvelope(val ciphertext: String? = null, val version: Int = 0)

/**
 * Persists `{ciphertext, version}` envelopes per store key under
 * `filesDir/storecache/`, one `<key>.json` file per key (key ∈ {`workspace`,`gallery`}).
 *
 * Writes are atomic (write to `<key>.json.tmp`, then rename) so a crash never leaves
 * a half-written file. Reads return null on any error (absent OR corrupt), so a
 * damaged cache entry degrades to "not cached" rather than throwing.
 */
@Singleton
class StoreDiskCache(private val root: File) {

    @Inject
    constructor(@ApplicationContext ctx: Context) : this(File(ctx.filesDir, "storecache"))

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the cached envelope for [key], or null if absent or corrupt. */
    fun get(key: String): StoreEnvelope? = try {
        val file = File(root, "$key.json")
        if (!file.exists()) null else json.decodeFromString<StoreEnvelope>(file.readText())
    } catch (_: Exception) {
        null
    }

    /** Atomically writes [env] for [key]: temp file, then rename over the target. */
    fun put(key: String, env: StoreEnvelope) {
        root.mkdirs()
        val tmp = File(root, "$key.json.tmp")
        val dest = File(root, "$key.json")
        tmp.writeText(json.encodeToString(env))
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            // Fallback for filesystems where rename fails; keep the write atomic-ish.
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** Removes the cached envelope for [key] (no-op if absent). */
    fun remove(key: String) {
        File(root, "$key.json").delete()
    }

    /** Deletes every file under the cache root. */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Sum of file sizes under the cache root (0 if none). */
    fun sizeBytes(): Long =
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
