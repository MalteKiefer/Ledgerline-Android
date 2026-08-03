package de.ledgerline.app.core.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.crypto.Crypto
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, **VK-sealed** queue of offline write [StoreDelta]s, one file per store key under
 * `filesDir/outbox/`. When a save can't reach the server the repository composes the record delta
 * of that edit and [append]s it here; on reconnect the sync engine reads it back, applies it to the
 * live server manifest, PUTs, and [clear]s the key.
 *
 * At-rest the delta contains plaintext record JSON, so it is sealed under the Vault Key exactly like
 * a store manifest (same `crypto.sealManifest` envelope, 4 KiB-padded) — nothing sensitive touches
 * disk in the clear (§11). Both [append] and [pending] therefore require the VK; they run only while
 * unlocked (the user must be unlocked to edit, and replay only runs unlocked).
 */
@Singleton
class SyncOutbox(private val root: File, private val crypto: Crypto) {

    @Inject
    constructor(@ApplicationContext ctx: Context, crypto: Crypto) : this(File(ctx.filesDir, "outbox"), crypto)

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(storeKey: String) = File(root, "$storeKey.json")

    /** Store keys with a pending delta (dirty stores). */
    fun keys(): Set<String> =
        (root.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension } ?: emptyList()).toSet()

    fun hasPending(): Boolean = keys().isNotEmpty()

    /** The pending (composed) delta for [storeKey], or null if none / unreadable / undecryptable. */
    fun pending(storeKey: String, vk: ByteArray): StoreDelta? {
        val f = fileFor(storeKey)
        if (!f.exists()) return null
        return try {
            // A file that exists but won't open (openManifest → null) was sealed under a VK that no
            // longer decrypts it — e.g. the vault was reset/re-provisioned (a "wipe everything"), so
            // the old VK is gone. Such an entry can NEVER replay; left in place it makes replayPending
            // return early forever without clearing, so every sync stays "pending" and never drains.
            // Self-heal: delete the dead entry (its data is unrecoverable anyway) so sync unblocks.
            val plain = crypto.openManifest(f.readText(), vk) ?: return dropDead(f)
            json.decodeFromString(StoreDelta.serializer(), plain)
        } catch (_: Exception) {
            dropDead(f) // corrupt / unparseable → same as undecryptable: it can never replay
        }
    }

    /** Delete an unreplayable (undecryptable/corrupt) outbox entry and report "nothing pending". */
    private fun dropDead(f: File): StoreDelta? {
        runCatching { f.delete() }
        return null
    }

    /** Compose [delta] onto any existing pending delta for [storeKey] and persist (atomic). */
    fun append(storeKey: String, delta: StoreDelta, vk: ByteArray) {
        if (delta.isEmpty) return
        val composed = (pending(storeKey, vk)?.then(delta)) ?: delta
        try {
            root.mkdirs()
            val plain = json.encodeToString(StoreDelta.serializer(), composed)
            val ct = crypto.sealManifest(plain, vk)
            val tmp = File(root, "$storeKey.json.tmp")
            tmp.writeText(ct)
            tmp.renameTo(fileFor(storeKey))
        } catch (_: Exception) { /* best-effort; a failed enqueue means the edit is only in-cache */ }
    }

    /** Drop the pending delta for [storeKey] (after a successful replay). */
    fun clear(storeKey: String) {
        try { fileFor(storeKey).delete() } catch (_: Exception) {}
    }

    /** Erase the whole outbox (forced logout / wipe). */
    fun clearAll() {
        try { root.deleteRecursively() } catch (_: Exception) {}
    }
}
