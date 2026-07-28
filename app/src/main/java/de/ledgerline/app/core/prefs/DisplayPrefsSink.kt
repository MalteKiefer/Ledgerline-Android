package de.ledgerline.app.core.prefs

/**
 * Write side of the global display preferences, so components that only persist prefs (the /me
 * adopt path, the appearance settings) don't depend on the whole [de.ledgerline.app.data.SettingsStore]
 * — and can be faked in tests. Implemented by `SettingsStore`.
 */
interface DisplayPrefsSink {
    suspend fun setDisplayPrefs(prefs: DisplayPrefs)
}
