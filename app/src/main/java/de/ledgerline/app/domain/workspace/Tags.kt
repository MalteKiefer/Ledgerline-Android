package de.ledgerline.app.domain.workspace

/**
 * Comma-separated tag parsing/formatting, matching the web client:
 * `value.split(',').map { trim }.filter { nonblank }`.
 */
object Tags {
    fun parseTags(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotBlank() }

    fun formatTags(tags: List<String>): String = tags.joinToString(", ")
}
