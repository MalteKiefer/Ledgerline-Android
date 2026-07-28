package de.ledgerline.app.ui.workspace

import de.ledgerline.app.R

/**
 * Every top-level destination in the redesigned shell. The first four are the primary
 * surfaces shown in the floating pill bar / navigation rail; the rest ("spaces" + system)
 * are reached from the navigation drawer or the Home hub tiles.
 */
enum class WorkspaceDest(val labelRes: Int) {
    Home(R.string.dest_home),
    Files(R.string.tab_files),
    Photos(R.string.tab_gallery),
    Vault(R.string.tab_passwords),
    Notes(R.string.tab_notes),
    Todos(R.string.tab_todos),
    Bookmarks(R.string.menu_bookmarks),
    Contacts(R.string.menu_contacts),
    Explore(R.string.dest_explore),
    Health(R.string.dest_health),
    Finance(R.string.dest_finance),
    Settings(R.string.settings_title);

    companion object {
        /** The four surfaces that appear in the pill bar / rail. */
        val primary = listOf(Home, Files, Photos, Vault)
    }
}
