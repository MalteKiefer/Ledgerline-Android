package de.ledgerline.app.data.finance

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable queue of offline finance mutations (plaintext on disk — the API itself is plaintext). Kept
 * deliberately simple for the pivot: only **update** and **delete** of records that already exist on
 * the server are queued (an offline *create* would need provisional-id remapping across foreign keys,
 * so it stays online-only). On reconnect [FinanceRepository] replays each op in FIFO order via the
 * normal per-record REST call (PUT carries the record `version` → 409 is reconciled on replay).
 */
@Serializable
data class FinanceOp(
    val entity: String,          // invoice | transaction | partner | paymentMethod | project | category
    val action: String,          // update | delete
    val id: Int,
    val body: JsonObject? = null, // update payload (null for delete)
)

@Singleton
class FinanceOutbox(private val file: File) {

    @Inject
    constructor(@ApplicationContext ctx: Context) : this(File(ctx.filesDir, "finance_outbox.json"))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun all(): List<FinanceOp> = try {
        if (file.exists()) json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FinanceOp.serializer()), file.readText()) else emptyList()
    } catch (_: Exception) { emptyList() }

    fun isEmpty(): Boolean = all().isEmpty()

    @Synchronized
    fun add(op: FinanceOp) = write(all() + op)

    /**
     * Drop any earlier op targeting the same (entity,id) and append [op] — the latest edit of a record
     * supersedes queued edits, and a delete drops queued updates. Keeps replay minimal + correct.
     */
    @Synchronized
    fun addCoalesced(op: FinanceOp) = write(all().filterNot { it.entity == op.entity && it.id == op.id } + op)

    @Synchronized
    fun remove(op: FinanceOp) = write(all().filterNot { it == op })

    @Synchronized
    fun clear() { runCatching { file.delete() } }

    private fun write(ops: List<FinanceOp>) {
        runCatching {
            if (ops.isEmpty()) { file.delete(); return }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FinanceOp.serializer()), ops))
            tmp.renameTo(file)
        }
    }
}
