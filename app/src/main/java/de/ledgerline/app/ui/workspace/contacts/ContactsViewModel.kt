package de.ledgerline.app.ui.workspace.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.data.ContactBlobRepository
import de.ledgerline.app.data.ContactSort
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.data.DeviceContactsSync
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.workspace.ContactOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject

data class ContactsUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val contacts: List<Contact> = emptyList(),
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
    private val mutate: MutateWorkspace,
    private val blobs: ContactBlobRepository,
    private val thumbs: ThumbCache,
    private val deviceSync: DeviceContactsSync,
    private val settings: SettingsStore,
) : ViewModel() {

    /** Date display format, for the detail screen to render birthdays/anniversaries. */
    val dateFormat: StateFlow<DateFormatPref> = settings.dateFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, DateFormatPref.SYSTEM)

    /** Whether URL taps show the browser chooser (mirrors bookmarks). */
    val linkChooser: StateFlow<Boolean> = settings.linkChooserEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var sort: ContactSort = ContactSort.FIRST
    private val _state = MutableStateFlow(ContactsUi(loading = true))
    val state: StateFlow<ContactsUi> = _state

    private val _showTrash = MutableStateFlow(false)
    val showTrash: StateFlow<Boolean> = _showTrash

    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly

    private val _allCategories = MutableStateFlow<List<String>>(emptyList())
    val allCategories: StateFlow<List<String>> = _allCategories

    private val _activeCategory = MutableStateFlow<String?>(null)
    val activeCategory: StateFlow<String?> = _activeCategory

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    init {
        viewModelScope.launch {
            cache.value.collect { ws ->
                if (ws != null) recompute() else _state.value = ContactsUi(loading = true)
            }
        }
        viewModelScope.launch {
            settings.contactSort.collect { sort = it; recompute() }
        }
    }

    fun refresh() = viewModelScope.launch {
        // Cache-first / stale-while-revalidate: if we already have decrypted data, keep
        // showing it and refresh silently in the background — no full-screen spinner, and
        // a network failure is ignored (we still have the cached list). Only when there is
        // nothing to show yet do we surface the spinner and, on failure, the error state.
        val haveData = cache.value.value != null
        if (!haveData) _state.value = _state.value.copy(loading = true, error = false)
        when (load.invoke()) {
            // Clear the spinner explicitly on success too: WorkspaceCache holds a data-class
            // Workspace, so a reload of unchanged data is value-equal and the StateFlow does
            // NOT re-emit — the cache collector would never fire and `loading` would stick.
            is Outcome.Ok -> recompute()
            is Outcome.Err -> if (!haveData) _state.value = _state.value.copy(loading = false, error = true)
        }
    }

    fun contactById(id: String): Contact? =
        cache.value.value?.manifest?.contacts?.firstOrNull { it.id == id }

    // ---- View toggles ----

    fun setQuery(q: String) { _query.value = q; recompute() }
    fun toggleFavoritesOnly() { _favoritesOnly.value = !_favoritesOnly.value; recompute() }
    fun setActiveCategory(cat: String?) { _activeCategory.value = cat; recompute() }
    fun setTrash(show: Boolean) { _showTrash.value = show; recompute() }
    fun clearMessage() { _message.value = null }

    // ---- Mutations ----

    /**
     * A fresh, NOT-yet-persisted blank contact. The editor opens on it immediately and
     * persists on the first [saveContact]; a still-blank contact is discarded (mirrors
     * NotesViewModel.newBlankNote). No empty contacts are created.
     */
    fun newBlankContact(): Contact = Contact(id = newId(), updated = nowIso())

    /** Persist an edit: upsert by id (append if new, update if it exists). Blank = discard. */
    fun saveContact(id: String, contact: Contact) {
        if (isBlank(contact)) return
        write { m ->
            val exists = m.contacts.any { it.id == id }
            val base = if (exists) m else ContactOps.addContact(m, id, nowIso())
            ContactOps.updateContact(base, id, contact, nowIso())
        }
    }

    fun toggleFavorite(id: String) = write { m -> ContactOps.toggleFavorite(m, id) }
    fun trash(id: String) = write { m -> ContactOps.trash(m, id) }
    fun restore(id: String) = write { m -> ContactOps.restore(m, id) }
    fun emptyTrash() {
        val freed = cache.value.value?.manifest?.contacts.orEmpty()
            .filter { it.trashed }.mapNotNull { it.avatarRef }
        write { m -> ContactOps.emptyTrash(m) }
        if (freed.isNotEmpty()) viewModelScope.launch { blobs.deleteBlobs(freed) }
    }

    fun deleteForever(id: String) {
        val ref = contactById(id)?.avatarRef
        write { m -> ContactOps.removeContact(m, id) }
        if (!ref.isNullOrBlank()) viewModelScope.launch { blobs.deleteBlobs(listOf(ref)) }
    }

    // ---- Avatar ----

    /** Decoded avatar bitmap for a contact, cached by avatarRef. Null on any failure. */
    suspend fun avatar(contact: Contact): Bitmap? {
        val ref = contact.avatarRef ?: return null
        val key = contact.avatarKey ?: return null
        thumbs.get(ref)?.let { return it }
        return when (val r = blobs.download(ref, key)) {
            is Outcome.Ok -> BitmapFactory.decodeByteArray(r.value, 0, r.value.size)?.also { thumbs.put(ref, it) }
            is Outcome.Err -> null
        }
    }

    /** Encrypt+upload a new avatar, point the contact at it, and free the old blob. */
    fun pickAvatar(id: String, bytes: ByteArray) = viewModelScope.launch {
        val old = contactById(id)?.avatarRef
        when (val r = blobs.uploadAvatar(bytes)) {
            is Outcome.Ok -> {
                if (mutate.invoke { m -> ContactOps.setAvatar(m, id, r.value.id, r.value.encFileKey, nowIso()) } is Outcome.Err) {
                    _message.value = "Save failed"
                } else if (!old.isNullOrBlank()) {
                    blobs.deleteBlobs(listOf(old))
                }
            }
            is Outcome.Err -> _message.value = "Upload failed"
        }
    }

    fun removeAvatar(id: String) = viewModelScope.launch {
        val old = contactById(id)?.avatarRef
        if (mutate.invoke { m -> ContactOps.clearAvatar(m, id, nowIso()) } is Outcome.Err) {
            _message.value = "Save failed"
        } else if (!old.isNullOrBlank()) {
            blobs.deleteBlobs(listOf(old))
        }
    }

    // ---- Device address-book sync (manual, permission-gated) ----

    /** Mirror every live vault contact into the device's local contacts (idempotent). */
    fun exportToDevice() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        try {
            val live = cache.value.value?.manifest?.contacts.orEmpty().filter { !it.trashed }
            val items = live.map { c ->
                val ref = c.avatarRef
                val key = c.avatarKey
                val avatar = if (!ref.isNullOrBlank() && !key.isNullOrBlank()) {
                    when (val r = blobs.download(ref, key)) {
                        is Outcome.Ok -> r.value
                        is Outcome.Err -> null
                    }
                } else null
                DeviceContactsSync.ExportItem(c, avatar)
            }
            val n = deviceSync.export(items)
            _message.value = "Exported $n contacts"
        } catch (e: Exception) {
            _message.value = "Export failed"
        } finally {
            _syncing.value = false
        }
    }

    /** Pull device contacts we don't already have into the vault (dedup by name/email/phone). */
    fun importFromDevice() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        try {
            val imported = deviceSync.import()
            val seen = HashSet<String>()
            cache.value.value?.manifest?.contacts.orEmpty().filter { !it.trashed }
                .forEach { seen.addAll(contactKeys(it)) }

            val toAdd = ArrayList<DeviceContactsSync.Imported>()
            for (imp in imported) {
                val ks = contactKeys(imp.contact)
                if (ks.any { it in seen }) continue
                seen.addAll(ks)
                toAdd.add(imp)
            }
            if (toAdd.isEmpty()) {
                _message.value = "No new contacts"
                return@launch
            }

            val now = nowIso()
            val normalized = toAdd.map { ContactOps.normalize(it.contact, newId(), now) }
            if (mutate.invoke { m -> ContactOps.addContacts(m, normalized) } is Outcome.Err) {
                _message.value = "Save failed"
                return@launch
            }

            // Upload any photos, then fold all avatar links into one manifest write.
            val avatarUpdates = ArrayList<Triple<String, String, String>>()
            toAdd.forEachIndexed { i, imp ->
                val photo = imp.photo ?: return@forEachIndexed
                when (val r = blobs.uploadAvatar(photo)) {
                    is Outcome.Ok -> avatarUpdates.add(Triple(normalized[i].id, r.value.id, r.value.encFileKey))
                    is Outcome.Err -> Unit
                }
            }
            if (avatarUpdates.isNotEmpty()) {
                mutate.invoke { m ->
                    avatarUpdates.fold(m) { acc, (id, ref, key) -> ContactOps.setAvatar(acc, id, ref, key, nowIso()) }
                }
            }
            _message.value = "Imported ${toAdd.size} contacts"
        } catch (e: Exception) {
            _message.value = "Import failed"
        } finally {
            _syncing.value = false
        }
    }

    /** Dedup keys: normalized name, each email, each phone (digits only). */
    private fun contactKeys(c: Contact): Set<String> {
        val ks = HashSet<String>()
        displayName(c).trim().lowercase().takeIf { it.isNotBlank() }?.let { ks.add("n:$it") }
        c.emails.forEach { e -> e.value.trim().lowercase().takeIf { it.isNotBlank() }?.let { ks.add("e:$it") } }
        c.phones.forEach { p -> p.value.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { ks.add("p:$it") } }
        return ks
    }

    // ---- Internals ----

    private fun isBlank(c: Contact): Boolean =
        c.fn.isBlank() && c.first.isBlank() && c.last.isBlank() && c.org.isBlank() &&
            c.nickname.isBlank() && c.note.isBlank() &&
            c.emails.none { it.value.isNotBlank() } && c.phones.none { it.value.isNotBlank() }

    private inline fun write(crossinline mutation: (WorkspaceManifest) -> WorkspaceManifest) =
        viewModelScope.launch {
            if (mutate.invoke { m -> mutation(m) } is Outcome.Err) _message.value = "Save failed"
        }

    private fun newId(): String = de.ledgerline.app.core.Ids.newId()
    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private fun displayName(c: Contact): String = when {
        c.last.isNotBlank() && c.first.isNotBlank() -> "${c.last}, ${c.first}"
        c.last.isNotBlank() -> c.last
        c.first.isNotBlank() -> c.first
        else -> c.fn.ifBlank { c.org }
    }

    /** Sort key per the chosen [ContactSort]; falls back to the display name when empty. */
    private fun sortKey(c: Contact): String = when (sort) {
        ContactSort.FIRST -> c.first.ifBlank { displayName(c) }
        ContactSort.LAST -> c.last.ifBlank { displayName(c) }
        ContactSort.DISPLAY -> displayName(c)
    }.lowercase()

    private fun matches(c: Contact, q: String): Boolean {
        if (q.isBlank()) return true
        val n = q.trim().lowercase()
        return displayName(c).contains(n, ignoreCase = true) ||
            c.first.contains(n, ignoreCase = true) ||
            c.last.contains(n, ignoreCase = true) ||
            c.org.contains(n, ignoreCase = true) ||
            c.emails.any { it.value.contains(n, ignoreCase = true) } ||
            c.phones.any { it.value.contains(n, ignoreCase = true) } ||
            c.categories.any { it.contains(n, ignoreCase = true) }
    }

    private fun recompute() {
        val manifest = cache.value.value?.manifest
        val all = manifest?.contacts.orEmpty()
        _trashCount.value = all.count { it.trashed }
        _allCategories.value = manifest?.let(ContactOps::allCategories).orEmpty()
        val cat = _activeCategory.value
        val list = if (_showTrash.value) {
            all.filter { it.trashed }.sortedBy { sortKey(it) }
        } else {
            all.filter {
                !it.trashed && matches(it, _query.value) &&
                    (!_favoritesOnly.value || it.favorite) &&
                    (cat == null || it.categories.any { g -> g.equals(cat, ignoreCase = true) })
            }.sortedBy { sortKey(it) }
        }
        _state.value = ContactsUi(false, false, list)
    }
}
