package de.ledgerline.app.ui.workspace.contacts

import de.ledgerline.app.R

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
    private val workspaceRepo: de.ledgerline.app.data.WorkspaceRepository,
    private val history: de.ledgerline.app.data.StoreHistoryRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private fun msg(resId: Int, vararg args: Any) { _message.value = context.getString(resId, *args) }

    // ---- Contacts version history / recovery (sharded /contacts/store, web v1.539) ----
    suspend fun historyVersions() = history.list(de.ledgerline.app.data.StoreHistoryRepository.Store.CONTACTS)
    suspend fun recoverVersion(version: Int): Int {
        val v = history.fetch(de.ledgerline.app.data.StoreHistoryRepository.Store.CONTACTS, version) ?: return -1
        val n = workspaceRepo.recoverContactsFromHistoryRoot(v.ciphertext)
        if (n > 0) load.invoke()
        return n
    }

    /** Date display format, for the detail screen to render birthdays/anniversaries. */
    val dateFormat: StateFlow<DateFormatPref> = settings.dateFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, DateFormatPref.SYSTEM)

    /** Whether URL taps show the browser chooser (mirrors bookmarks). */
    val linkChooser: StateFlow<Boolean> = settings.linkChooserEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var sort: ContactSort = ContactSort.FIRST

    /** Chosen name-display order (Last, First vs First Last) for the list/detail. */
    val nameOrder: StateFlow<de.ledgerline.app.data.ContactNameOrder> = settings.contactNameOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, de.ledgerline.app.data.ContactNameOrder.LAST_FIRST)

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
        if (freed.isNotEmpty()) viewModelScope.launch { blobs.deleteBlobs(freed); reconcileAvatars() }
    }

    fun deleteForever(id: String) {
        val ref = contactById(id)?.avatarRef
        write { m -> ContactOps.removeContact(m, id) }
        if (!ref.isNullOrBlank()) viewModelScope.launch { blobs.deleteBlobs(listOf(ref)); reconcileAvatars() }
    }

    /** Best-effort living-set reconcile of contact avatar blobs (reclaims failed eager deletes). */
    private suspend fun reconcileAvatars() {
        val living = cache.value.value?.manifest?.contacts.orEmpty().mapNotNull { it.avatarRef }.filter { it.isNotBlank() }
        blobs.reconcile(living)
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
                    msg(R.string.contact_save_failed)
                } else if (!old.isNullOrBlank()) {
                    blobs.deleteBlobs(listOf(old))
                }
            }
            is Outcome.Err -> msg(R.string.contact_upload_failed)
        }
    }

    fun removeAvatar(id: String) = viewModelScope.launch {
        val old = contactById(id)?.avatarRef
        if (mutate.invoke { m -> ContactOps.clearAvatar(m, id, nowIso()) } is Outcome.Err) {
            msg(R.string.contact_save_failed)
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
            msg(R.string.contacts_export_done, n)
        } catch (e: Exception) {
            msg(R.string.contacts_export_failed)
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Pull device contacts into the vault: contacts that MATCH an existing one (by name/email/phone)
     * are UPDATED (scalar fields refreshed if the device has a value; email/phone/url/address lists
     * unioned — never destructive), the rest are added new. Matched via [contactKeys].
     */
    fun importFromDevice() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        try {
            val imported = deviceSync.import()
            val existing = cache.value.value?.manifest?.contacts.orEmpty().filter { !it.trashed }
            // key → existing contact (first-wins) to resolve a device contact to the one it updates.
            val byKey = HashMap<String, Contact>()
            existing.forEach { c -> contactKeys(c).forEach { byKey.putIfAbsent(it, c) } }

            val now = nowIso()
            val toAdd = ArrayList<Pair<Contact, DeviceContactsSync.Imported>>()   // normalized-new, source
            val toUpdate = ArrayList<Pair<Contact, DeviceContactsSync.Imported>>() // merged-existing, source
            val claimed = HashSet<String>() // existing ids already matched this run
            for (imp in imported) {
                val match = contactKeys(imp.contact).firstNotNullOfOrNull { byKey[it] }
                    ?.takeIf { it.id !in claimed }
                if (match != null) {
                    claimed.add(match.id)
                    toUpdate.add(mergeDeviceContact(match, imp.contact, now) to imp)
                } else {
                    toAdd.add(ContactOps.normalize(imp.contact, newId(), now) to imp)
                }
            }
            if (toAdd.isEmpty() && toUpdate.isEmpty()) {
                msg(R.string.contacts_import_none)
                return@launch
            }

            if (mutate.invoke { m ->
                    var mm = ContactOps.addContacts(m, toAdd.map { it.first })
                    toUpdate.forEach { (c, _) -> mm = ContactOps.updateContact(mm, c.id, c, now) }
                    mm
                } is Outcome.Err) {
                msg(R.string.contact_save_failed)
                return@launch
            }

            // Upload any photos (new + updated that carry a device photo), fold avatar links in one write.
            val avatarUpdates = ArrayList<Triple<String, String, String>>()
            (toAdd + toUpdate).forEach { (c, imp) ->
                val photo = imp.photo ?: return@forEach
                when (val r = blobs.uploadAvatar(photo)) {
                    is Outcome.Ok -> avatarUpdates.add(Triple(c.id, r.value.id, r.value.encFileKey))
                    is Outcome.Err -> Unit
                }
            }
            if (avatarUpdates.isNotEmpty()) {
                mutate.invoke { m ->
                    avatarUpdates.fold(m) { acc, (id, ref, key) -> ContactOps.setAvatar(acc, id, ref, key, nowIso()) }
                }
            }
            msg(R.string.contacts_import_done, toAdd.size, toUpdate.size)
        } catch (e: Exception) {
            msg(R.string.contacts_import_failed)
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Non-destructive refresh of an existing vault contact from a device contact: overwrite scalar
     * fields only when the device provides a value; union the email/phone/impp/url/address lists (add
     * device entries not already present). Vault-only data (id, categories, favorite, avatar, personId,
     * uid, trashed, raw) is preserved.
     */
    private fun mergeDeviceContact(existing: Contact, dev: Contact, nowIso: String): Contact {
        fun pick(cur: String, new: String) = new.ifBlank { cur }
        fun mergeLabeled(cur: List<de.ledgerline.app.domain.model.LabeledValue>, new: List<de.ledgerline.app.domain.model.LabeledValue>) =
            cur + new.filter { d -> d.value.isNotBlank() && cur.none { it.value.equals(d.value, ignoreCase = true) } }
        return existing.copy(
            fn = pick(existing.fn, dev.fn),
            first = pick(existing.first, dev.first), last = pick(existing.last, dev.last), middle = pick(existing.middle, dev.middle),
            prefix = pick(existing.prefix, dev.prefix), suffix = pick(existing.suffix, dev.suffix), nickname = pick(existing.nickname, dev.nickname),
            org = pick(existing.org, dev.org), department = pick(existing.department, dev.department),
            title = pick(existing.title, dev.title), role = pick(existing.role, dev.role),
            emails = mergeLabeled(existing.emails, dev.emails),
            phones = mergeLabeled(existing.phones, dev.phones),
            impp = mergeLabeled(existing.impp, dev.impp),
            urls = mergeLabeled(existing.urls, dev.urls),
            addresses = existing.addresses + dev.addresses.filter { it !in existing.addresses },
            bday = pick(existing.bday, dev.bday), anniversary = pick(existing.anniversary, dev.anniversary),
            note = pick(existing.note, dev.note),
            updated = nowIso,
        )
    }

    /**
     * Merge duplicate contacts (same person stored more than once — e.g. from a re-import before the
     * format-independent matching): cluster by shared [contactKeys] (union-find), keep one survivor
     * per cluster, fold every duplicate's fields into it (union lists, fill blanks, union categories,
     * keep an avatar), then remove the duplicates. Loss-free — nothing unique is dropped.
     */
    fun mergeDuplicates() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        try {
            val live = cache.value.value?.manifest?.contacts.orEmpty().filter { !it.trashed }
            val parent = HashMap<String, String>()
            fun find(x: String): String { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
            fun union(a: String, b: String) { parent.putIfAbsent(a, a); parent.putIfAbsent(b, b); parent[find(a)] = find(b) }
            live.forEach { parent.putIfAbsent(it.id, it.id) }
            val keyToId = HashMap<String, String>()
            live.forEach { c -> contactKeys(c).forEach { k -> keyToId[k]?.let { union(c.id, it) } ?: run { keyToId[k] = c.id } } }

            val updById = HashMap<String, Contact>()
            val removeIds = HashSet<String>()
            var mergedDups = 0
            live.groupBy { find(it.id) }.values.forEach { members ->
                if (members.size < 2) return@forEach
                val survivor = members.firstOrNull { !it.avatarRef.isNullOrBlank() }
                    ?: members.maxByOrNull { fieldScore(it) } ?: members.first()
                var s = survivor
                members.filter { it.id != survivor.id }.forEach { dup -> s = mergeContacts(s, dup); removeIds.add(dup.id); mergedDups++ }
                updById[survivor.id] = s.copy(updated = nowIso())
            }
            if (removeIds.isEmpty()) { msg(R.string.contacts_dedup_none); return@launch }
            // Direct manifest rewrite (not updateContact, which would re-pull the survivor's old avatar).
            mutate.invoke { m -> m.copy(contacts = m.contacts.mapNotNull { c -> if (c.id in removeIds) null else updById[c.id] ?: c }) }
            reconcileAvatars() // freed duplicate-avatar blobs
            msg(R.string.contacts_dedup_done, mergedDups)
        } catch (e: Exception) {
            msg(R.string.contact_save_failed)
        } finally {
            _syncing.value = false
        }
    }

    /** Fold contact [b]'s data into [a] (a wins scalars/avatar; lists + categories unioned). */
    private fun mergeContacts(a: Contact, b: Contact): Contact {
        fun pick(cur: String, alt: String) = cur.ifBlank { alt }
        fun ml(cur: List<de.ledgerline.app.domain.model.LabeledValue>, alt: List<de.ledgerline.app.domain.model.LabeledValue>) =
            cur + alt.filter { d -> d.value.isNotBlank() && cur.none { it.value.equals(d.value, ignoreCase = true) } }
        return a.copy(
            fn = pick(a.fn, b.fn), first = pick(a.first, b.first), last = pick(a.last, b.last), middle = pick(a.middle, b.middle),
            prefix = pick(a.prefix, b.prefix), suffix = pick(a.suffix, b.suffix), nickname = pick(a.nickname, b.nickname),
            org = pick(a.org, b.org), department = pick(a.department, b.department), title = pick(a.title, b.title), role = pick(a.role, b.role),
            vatId = pick(a.vatId, b.vatId),
            emails = ml(a.emails, b.emails), phones = ml(a.phones, b.phones), impp = ml(a.impp, b.impp), urls = ml(a.urls, b.urls),
            addresses = a.addresses + b.addresses.filter { it !in a.addresses },
            bday = pick(a.bday, b.bday), anniversary = pick(a.anniversary, b.anniversary), note = pick(a.note, b.note),
            categories = (a.categories + b.categories).distinct(),
            favorite = a.favorite || b.favorite,
            avatarRef = a.avatarRef ?: b.avatarRef, avatarKey = if (!a.avatarRef.isNullOrBlank()) a.avatarKey else b.avatarKey,
            uid = a.uid ?: b.uid, personId = a.personId ?: b.personId, personName = a.personName ?: b.personName,
        )
    }

    private fun fieldScore(c: Contact): Int =
        listOf(c.fn, c.first, c.last, c.org, c.title, c.note, c.bday).count { it.isNotBlank() } +
            c.emails.size + c.phones.size + c.addresses.size + (if (!c.avatarRef.isNullOrBlank()) 2 else 0)

    /**
     * Match/dedup keys, FORMAT-INDEPENDENT so the same person matches regardless of how the name is
     * stored (a device `fn` "John Doe" vs a vault first/last "Doe"/"John") or how a phone is written
     * (`+49 170…` vs `0170…`):
     *  - name = the case-folded name tokens (fn+first+middle+last+nickname) **sorted** (order-free);
     *  - email = exact case-folded value;
     *  - phone = the last 9 significant digits (strips country code + leading zero + formatting).
     */
    private fun contactKeys(c: Contact): Set<String> {
        val ks = HashSet<String>()
        val nameTokens = "${c.fn} ${c.first} ${c.middle} ${c.last} ${c.nickname}"
            .split(' ', '\t', ',', '\n', '\r').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (nameTokens.isNotEmpty()) ks.add("n:" + nameTokens.toSortedSet().joinToString(" "))
        c.emails.forEach { e -> e.value.trim().lowercase().takeIf { it.isNotBlank() }?.let { ks.add("e:$it") } }
        c.phones.forEach { p ->
            val d = p.value.filter { it.isDigit() }
            if (d.isNotBlank()) ks.add("p:" + if (d.length >= 7) d.takeLast(9) else d)
        }
        return ks
    }

    // ---- Internals ----

    private fun isBlank(c: Contact): Boolean =
        c.fn.isBlank() && c.first.isBlank() && c.last.isBlank() && c.org.isBlank() &&
            c.nickname.isBlank() && c.note.isBlank() &&
            c.emails.none { it.value.isNotBlank() } && c.phones.none { it.value.isNotBlank() }

    private inline fun write(crossinline mutation: (WorkspaceManifest) -> WorkspaceManifest) =
        viewModelScope.launch {
            if (mutate.invoke { m -> mutation(m) } is Outcome.Err) msg(R.string.contact_save_failed)
        }

    private fun newId(): String = de.ledgerline.app.core.Ids.newId()
    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    // ---- vCard import / export (RFC 6350) ----

    /** All non-trashed contacts serialised as a vCard 4.0 (.vcf) document. */
    fun exportVcf(): String {
        val contacts = cache.value.value?.manifest?.contacts.orEmpty().filter { !it.trashed }
        return de.ledgerline.app.core.contacts.VCard.export(contacts)
    }

    /**
     * Parse a `.vcf` file and add each contact (fresh id + timestamp). Returns the number added via
     * [onDone]; 0 on a parse yielding no cards. Avatars are not imported (separate encrypted blob).
     */
    fun importVcf(text: String, onDone: (Int) -> Unit = {}) {
        val parsed = runCatching { de.ledgerline.app.core.contacts.VCard.parse(text) }.getOrDefault(emptyList())
            .map { it.copy(id = newId(), updated = nowIso()) }
        if (parsed.isEmpty()) { onDone(0); return }
        viewModelScope.launch {
            val ok = mutate.invoke { m -> m.copy(contacts = m.contacts + parsed) } is Outcome.Ok
            if (!ok) msg(R.string.contacts_import_failed)
            onDone(if (ok) parsed.size else 0)
        }
    }

    private fun displayName(c: Contact): String = contactDisplayName(c, nameOrder.value)

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
