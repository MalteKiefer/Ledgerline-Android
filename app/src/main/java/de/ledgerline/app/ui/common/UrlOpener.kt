package de.ledgerline.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import de.ledgerline.app.R

/**
 * Open a link, guarding to `http(s)` only (matching the web client, which rejects
 * non-http(s) schemes). When [chooser] is true the intent is wrapped in a system
 * app-chooser so the user can pick which browser handles it. Failures are swallowed
 * (no activity to handle it, etc.) so a bad url never crashes the caller.
 */
fun openUrl(context: Context, url: String, chooser: Boolean) {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return
    val view = Intent(Intent.ACTION_VIEW, trimmed.toUri())
    val intent =
        if (chooser) Intent.createChooser(view, context.getString(R.string.open_with)) else view
    runCatching { context.startActivity(intent) }
}
