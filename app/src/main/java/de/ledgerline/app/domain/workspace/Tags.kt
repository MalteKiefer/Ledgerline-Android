package de.ledgerline.app.domain.workspace

/**
 * Comma-separated tag parsing/formatting, matching the web client:
 * `value.split(',').map { trim }.filter { nonblank }`.
 */
object Tags {
    fun parseTags(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotBlank() }

    fun formatTags(tags: List<String>): String = tags.joinToString(", ")

    /**
     * The sorted, distinct union of all tags across a collection of items, mirroring the
     * web client's `allTags`. Distinctness is case-insensitive (first-seen casing wins);
     * the result is sorted case-insensitively. Blank tags are dropped.
     */
    fun union(itemTags: List<List<String>>): List<String> =
        itemTags.asSequence()
            .flatten()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .toList()

    /** True when [tags] contains [tag] by exact, case-insensitive match. */
    fun contains(tags: List<String>, tag: String): Boolean =
        tags.any { it.equals(tag, ignoreCase = true) }

    /**
     * Fold a still-uncommitted chip-input [draft] into [tags] (parse it, append, drop
     * case-insensitive duplicates). Lets an editor save a tag the user typed but didn't
     * yet turn into a chip with comma/enter — no silent loss.
     */
    fun mergeDraft(tags: List<String>, draft: String): List<String> {
        val extra = parseTags(draft).filter { p -> tags.none { it.equals(p, ignoreCase = true) } }
        return tags + extra
    }
}
