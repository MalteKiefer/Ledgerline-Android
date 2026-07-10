package de.ledgerline.app.ui.workspace.common

import java.util.Locale

/** Human-readable byte size, base-1024, one decimal above KB. */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}
