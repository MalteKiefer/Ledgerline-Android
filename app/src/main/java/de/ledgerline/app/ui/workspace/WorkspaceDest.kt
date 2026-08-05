package de.ledgerline.app.ui.workspace

import de.ledgerline.app.R

/**
 * Every top-level destination in the redesigned shell. The first four are the primary
 * surfaces shown in the floating pill bar / navigation rail; the rest ("spaces" + system)
 * are reached from the navigation drawer or the Home hub tiles.
 */
enum class WorkspaceDest(val labelRes: Int, val moduleKey: String? = null) {
    Home(R.string.dest_home),                              // hub — always shown
    Files(R.string.tab_files, "files"),
    Photos(R.string.tab_gallery, "gallery"),
    Vault(R.string.tab_passwords, "passwords"),
    Notes(R.string.tab_notes, "notes"),
    Todos(R.string.tab_todos, "todos"),
    Bookmarks(R.string.menu_bookmarks, "bookmarks"),
    Contacts(R.string.menu_contacts, "contacts"),
    Explore(R.string.dest_explore, "explore"),
    Health(R.string.dest_health, "health"),
    Calendar(R.string.dest_calendar, "calendar"),
    Finance(R.string.dest_finance, "finance"),
    Settings(R.string.settings_title);                    // system — always shown

    /** Whether this destination is permitted by the account's module entitlements. */
    fun isAllowed(access: de.ledgerline.app.core.ModuleAccess): Boolean =
        moduleKey == null || access.allows(moduleKey)

    companion object {
        /** The four surfaces that appear in the pill bar / rail. */
        val primary = listOf(Home, Files, Photos, Vault)
    }
}
