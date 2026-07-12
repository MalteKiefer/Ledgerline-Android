package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.WorkspaceManifest

/**
 * Pure manifest transforms for contact management. Each returns a new
 * [WorkspaceManifest]; mutating an unknown id is a safe no-op. Mirrors the web
 * `contacts` Alpine component (see [BookmarkOps]/[NoteOps] for the same style).
 */
object ContactOps {

    /** Append a fresh blank contact (only `id` + `updated` set). */
    fun addContact(m: WorkspaceManifest, id: String, nowIso: String): WorkspaceManifest =
        m.copy(contacts = m.contacts + Contact(id = id, updated = nowIso))

    /**
     * Replace the matching contact's editable fields with [contact], stamping `updated`.
     * Mirrors the web `save()`: `fn` falls back to `"$first $last".trim()` when blank.
     * The id/avatar/notification/preserved-props fields are kept from the stored record.
     */
    fun updateContact(m: WorkspaceManifest, id: String, contact: Contact, nowIso: String): WorkspaceManifest =
        update(m, id) { existing ->
            val fn = contact.fn.trim().ifBlank { "${contact.first.trim()} ${contact.last.trim()}".trim() }
            contact.copy(
                id = existing.id,
                fn = fn,
                emails = contact.emails.map { it.copy(type = normType(it.type, "home")) },
                phones = contact.phones.map { it.copy(type = normType(it.type, "cell")) },
                impp = contact.impp.map { it.copy(type = normType(it.type, "home")) },
                urls = contact.urls.map { it.copy(type = normType(it.type, "home")) },
                addresses = contact.addresses.map { it.copy(type = normType(it.type, "home")) },
                avatarRef = existing.avatarRef,
                avatarKey = existing.avatarKey,
                bdayNotified = existing.bdayNotified,
                annivNotified = existing.annivNotified,
                _x = existing._x,
                updated = nowIso,
            )
        }

    /**
     * Normalize a freshly-built contact (e.g. imported from the device address book):
     * assign [id], derive `fn` from first/last when blank, and canonicalize every TYPE
     * label — the same rules [updateContact] applies to an edited record.
     */
    fun normalize(c: Contact, id: String, nowIso: String): Contact {
        val fn = c.fn.trim().ifBlank { "${c.first.trim()} ${c.last.trim()}".trim() }
        return c.copy(
            id = id,
            fn = fn,
            emails = c.emails.map { it.copy(type = normType(it.type, "home")) },
            phones = c.phones.map { it.copy(type = normType(it.type, "cell")) },
            impp = c.impp.map { it.copy(type = normType(it.type, "home")) },
            urls = c.urls.map { it.copy(type = normType(it.type, "home")) },
            addresses = c.addresses.map { it.copy(type = normType(it.type, "home")) },
            updated = nowIso,
        )
    }

    /** Append many already-normalized contacts in one shot (device import). */
    fun addContacts(m: WorkspaceManifest, contacts: List<Contact>): WorkspaceManifest =
        m.copy(contacts = m.contacts + contacts)

    fun toggleFavorite(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(favorite = !it.favorite) }

    fun trash(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(trashed = true) }

    fun restore(m: WorkspaceManifest, id: String): WorkspaceManifest =
        update(m, id) { it.copy(trashed = false) }

    /** Delete forever: drop the contact from the list. Unknown id = no-op. */
    fun removeContact(m: WorkspaceManifest, id: String): WorkspaceManifest =
        m.copy(contacts = m.contacts.filterNot { it.id == id })

    /** Empty the trash: drop every trashed contact. */
    fun emptyTrash(m: WorkspaceManifest): WorkspaceManifest =
        m.copy(contacts = m.contacts.filterNot { it.trashed })

    /** Set/replace a contact's avatar reference + wrapped key. */
    fun setAvatar(m: WorkspaceManifest, id: String, ref: String, key: String, nowIso: String): WorkspaceManifest =
        update(m, id) { it.copy(avatarRef = ref, avatarKey = key, updated = nowIso) }

    /** Clear a contact's avatar reference. */
    fun clearAvatar(m: WorkspaceManifest, id: String, nowIso: String): WorkspaceManifest =
        update(m, id) { it.copy(avatarRef = null, avatarKey = null, updated = nowIso) }

    /** Sorted, case-insensitive distinct union of non-trashed contacts' categories. */
    fun allCategories(m: WorkspaceManifest): List<String> =
        m.contacts.filter { !it.trashed }.map { it.categories }.let(Tags::union)

    /**
     * Reduce a vCard TYPE list (e.g. "cell,voice,pref") to one known label, mirroring
     * the web `VCard.normType`: cell/mobile → cell, work → work, home → home, else the
     * given [default].
     */
    fun normType(raw: String, default: String): String {
        val toks = raw.lowercase().split(',', ';').map { it.trim() }
        return when {
            toks.any { it == "cell" || it == "mobile" } -> "cell"
            toks.contains("work") -> "work"
            toks.contains("home") -> "home"
            else -> default
        }
    }

    private inline fun update(
        m: WorkspaceManifest,
        id: String,
        transform: (Contact) -> Contact,
    ): WorkspaceManifest =
        m.copy(contacts = m.contacts.map { if (it.id == id) transform(it) else it })
}
