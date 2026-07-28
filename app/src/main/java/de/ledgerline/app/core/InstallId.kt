package de.ledgerline.app.core

import android.content.Context
import java.util.UUID

/**
 * A stable, non-secret per-install identifier sent when pairing so the server can dedupe a
 * re-pair of the same install (replacing the device row) instead of stacking duplicates that
 * lead to accidental logout loops. Generated once and persisted in plain prefs.
 */
object InstallId {
    private const val PREFS = "ll_install"
    private const val KEY = "install_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY, it).apply()
        }
    }
}
