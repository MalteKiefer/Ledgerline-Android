package de.ledgerline.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import de.ledgerline.app.R

/**
 * Open a link, guarding to `http(s)` only (matching the web client, which rejects
 * non-http(s) schemes).
 *
 * When [chooser] is true the user should pick which browser handles it. A plain
 * `Intent.createChooser` is unreliable for web links on modern Android/GrapheneOS —
 * a set default-browser role can short-circuit the picker and open the default
 * directly. So we enumerate the installed browsers ourselves (needs the `<queries>`
 * entry in AndroidManifest for Android 11+ package visibility) and build an explicit
 * chooser from per-browser intents via [Intent.EXTRA_INITIAL_INTENTS], which forces
 * the picker to show every browser regardless of any default.
 *
 * Failures are swallowed so a bad url never crashes the caller.
 */
fun openUrl(context: Context, url: String, chooser: Boolean) {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return
    val uri = trimmed.toUri()
    val view = Intent(Intent.ACTION_VIEW, uri)

    if (!chooser) {
        runCatching { context.startActivity(view) }
        return
    }

    // Enumerate browsers via a generic web probe (a bare https host, so it resolves to
    // browsers rather than an app that only claims a specific domain).
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_VIEW, "https://www.example.com".toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browserPkgs = runCatching {
        pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    if (browserPkgs.size <= 1) {
        // Zero or one browser → nothing to choose; just open.
        runCatching { context.startActivity(view) }
        return
    }

    // One explicit per-browser intent each; the first seeds the chooser, the rest go
    // in EXTRA_INITIAL_INTENTS so the picker lists them all and ignores any default.
    val perBrowser = browserPkgs.map { pkg -> Intent(Intent.ACTION_VIEW, uri).setPackage(pkg) }
    val chooserIntent = Intent.createChooser(perBrowser.first(), context.getString(R.string.open_with))
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, perBrowser.drop(1).toTypedArray())
    runCatching { context.startActivity(chooserIntent) }
}
