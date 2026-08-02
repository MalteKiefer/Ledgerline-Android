package de.ledgerline.app.core.offline

/**
 * A repository whose sealed store(s) can hold offline edits in the [SyncOutbox] and replay them when
 * the network returns. Implementations capture a record [StoreDelta] when a save fails on the
 * network (persisting it optimistically to the cache + outbox), and re-apply the pending delta onto
 * the live server manifest via their existing `save(mutate)` path in [replayPending].
 */
interface SyncableStore {
    /** Human-readable label for logs/UI (e.g. "passwords", "workspace"). */
    val syncLabel: String

    /** Replay every pending offline delta this repo owns onto the server head. Returns true when all
     *  owned keys are cleared (nothing left pending), false if something remained (retry later). */
    suspend fun replayPending(): Boolean

    /**
     * True when this store holds pending work that lives OUTSIDE the shared [SyncOutbox] (e.g. the
     * blob-import queue), so a sync pass runs even when the manifest outbox is empty. Defaults false —
     * outbox-only stores are already covered by [SyncOutbox.hasPending].
     */
    fun hasPendingWork(): Boolean = false
}
