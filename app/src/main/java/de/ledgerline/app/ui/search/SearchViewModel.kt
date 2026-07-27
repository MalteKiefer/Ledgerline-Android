package de.ledgerline.app.ui.search

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.ui.workspace.WorkspaceDest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** One cross-module search hit. [dest] is the module the hit lives in (tapping routes there). */
data class SearchHit(val dest: WorkspaceDest, val title: String, val subtitle: String)

/**
 * Global search across the already-decrypted in-memory caches (files, photos, passwords, notes,
 * bookmarks, contacts). Plain case-insensitive substring match on the fields a person recognises —
 * zero-knowledge safe (nothing leaves the device; the server never sees the query).
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val workspaceCache: WorkspaceCache,
    private val galleryCache: GalleryCache,
    private val passwordsCache: PasswordsCache,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = _results.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
        _results.value = if (q.isBlank()) emptyList() else compute(q.trim().lowercase())
    }

    private fun compute(q: String): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        val ws = workspaceCache.value.value?.manifest
        ws?.files?.asSequence()?.filter { !it.trashed && it.name.lowercase().contains(q) }?.take(6)
            ?.forEach { out.add(SearchHit(WorkspaceDest.Files, it.name, sub(it.mime))) }
        ws?.notes?.asSequence()?.filter { !it.trashed && (it.title.lowercase().contains(q) || it.content.lowercase().contains(q)) }?.take(6)
            ?.forEach { out.add(SearchHit(WorkspaceDest.Notes, it.title.ifBlank { it.content.take(40) }, "Note")) }
        ws?.bookmarks?.asSequence()?.filter { !it.trashed && (it.title.lowercase().contains(q) || it.url.lowercase().contains(q)) }?.take(6)
            ?.forEach { out.add(SearchHit(WorkspaceDest.Bookmarks, it.title.ifBlank { it.url }, it.url)) }
        ws?.contacts?.asSequence()?.filter { !it.trashed && ("${'$'}{it.fn} ${'$'}{it.first} ${'$'}{it.last} ${'$'}{it.org}").lowercase().contains(q) }?.take(6)
            ?.forEach { c -> out.add(SearchHit(WorkspaceDest.Contacts, c.fn.ifBlank { (c.first + " " + c.last).trim() }.ifBlank { c.org }, "Contact")) }
        passwordsCache.value.value?.manifest?.secrets?.asSequence()
            ?.filter { !it.isTrashed && it.title.lowercase().contains(q) }?.take(6)
            ?.forEach { out.add(SearchHit(WorkspaceDest.Vault, it.title, it.type)) }
        galleryCache.value.value?.manifest?.photos?.asSequence()
            ?.filter { !it.trashed && (it.name?.lowercase()?.contains(q) == true) }?.take(6)
            ?.forEach { out.add(SearchHit(WorkspaceDest.Photos, it.name ?: "Photo", "Photo")) }
        return out
    }

    private fun sub(mime: String) = mime.substringAfterLast('/').uppercase().ifBlank { "File" }
}
