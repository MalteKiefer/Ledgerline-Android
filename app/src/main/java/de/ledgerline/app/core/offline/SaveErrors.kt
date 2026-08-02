package de.ledgerline.app.core.offline

import de.ledgerline.app.core.ErrorKind

/**
 * Save failures that are transient/retryable → the edit must be queued to the durable
 * [SyncOutbox] and replayed later instead of being dropped, keeping the optimistic cache.
 *
 * A sealed-store PUT only ever fails with these on the server side: a dropped socket
 * (NETWORK), a 5xx / 429 / exhausted-409-conflict retry loop (all collapse to HTTP), or a
 * throttle (RATE_LIMITED). The store validates no record *content*, so there is no permanent
 * 4xx to loop on. DECRYPT / WRONG_PASSPHRASE / GONE are deliberately NOT here — those can't be
 * fixed by retrying, so the edit is reverted instead.
 *
 * Single source of truth for every repository's `save()` fallback (workspace, health, explore,
 * gallery, passwords, finance) so a server hiccup never silently loses a user's edit.
 */
val RECOVERABLE_SAVE_ERRORS = setOf(ErrorKind.NETWORK, ErrorKind.HTTP, ErrorKind.RATE_LIMITED)
