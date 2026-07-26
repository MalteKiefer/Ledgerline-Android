package de.ledgerline.app.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Clipboard helper that flags copied secrets as **sensitive** (so the OS keeps them out of
 * clipboard previews / history) and auto-clears them after a delay — matching the iOS
 * transient-pasteboard hardening. Use [copySensitive] for passwords/TOTP/card numbers.
 */
object SecureClipboard {
    private const val CLEAR_AFTER_MS = 60_000L

    fun copySensitive(context: Context, label: String, text: String) =
        copy(context, label, text, sensitive = true)

    fun copyPlain(context: Context, label: String, text: String) =
        copy(context, label, text, sensitive = false)

    private fun copy(context: Context, label: String, text: String, sensitive: Boolean) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        if (sensitive) {
            Handler(Looper.getMainLooper()).postDelayed({
                // Only clear if the clipboard still holds our value (don't wipe a later copy).
                val current = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
                if (current == text) runCatching { cm.clearPrimaryClip() }
            }, CLEAR_AFTER_MS)
        }
    }
}
