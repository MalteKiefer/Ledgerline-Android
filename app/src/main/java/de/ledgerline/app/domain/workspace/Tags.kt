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
}
