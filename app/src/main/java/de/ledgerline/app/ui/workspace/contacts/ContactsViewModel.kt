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
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.workspace.ContactOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
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
) : ViewModel() {
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

    init {
        viewModelScope.launch {
            cache.value.collect { ws ->
                if (ws != null) recompute() else _state.value = ContactsUi(loading = true)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) {
            _state.value = _state.value.copy(loading = false, error = true)
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

    // ---- Internals ----

    private fun isBlank(c: Contact): Boolean =
        c.fn.isBlank() && c.first.isBlank() && c.last.isBlank() && c.org.isBlank() &&
            c.nickname.isBlank() && c.note.isBlank() &&
            c.emails.none { it.value.isNotBlank() } && c.phones.none { it.value.isNotBlank() }

    private inline fun write(crossinline mutation: (WorkspaceManifest) -> WorkspaceManifest) =
        viewModelScope.launch {
            if (mutate.invoke { m -> mutation(m) } is Outcome.Err) _message.value = "Save failed"
        }

    private fun newId(): String = UUID.randomUUID().toString()
    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private fun displayName(c: Contact): String =
        c.fn.ifBlank { "${c.first} ${c.last}".trim() }.ifBlank { c.org }

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
            all.filter { it.trashed }.sortedBy { displayName(it).lowercase() }
        } else {
            all.filter {
                !it.trashed && matches(it, _query.value) &&
                    (!_favoritesOnly.value || it.favorite) &&
                    (cat == null || it.categories.any { g -> g.equals(cat, ignoreCase = true) })
            }.sortedBy { displayName(it).lowercase() }
        }
        _state.value = ContactsUi(false, false, list)
    }
}
