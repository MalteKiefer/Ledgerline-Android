package de.ledgerline.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.domain.usecase.FilesUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Aggregates the home hub's headline numbers from the already-decrypted in-memory caches —
 * no extra network. Counts recompute whenever any module cache changes; storage usage is a
 * one-shot best-effort fetch.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    workspaceCache: WorkspaceCache,
    galleryCache: GalleryCache,
    passwordsCache: PasswordsCache,
    private val filesUsage: FilesUsage,
    private val account: AccountRepository,
) : ViewModel() {

    data class Counts(
        val notes: Int = 0,
        val todosOpen: Int = 0,
        val bookmarks: Int = 0,
        val contacts: Int = 0,
        val files: Int = 0,
        val photos: Int = 0,
        val vault: Int = 0,
    )

    val counts: StateFlow<Counts> = combine(
        workspaceCache.value,
        galleryCache.value,
        passwordsCache.value,
    ) { ws, gal, pw ->
        val m = ws?.manifest
        Counts(
            notes = m?.notes?.count { !it.trashed } ?: 0,
            todosOpen = m?.todos?.count { !it.trashed && !it.done } ?: 0,
            bookmarks = m?.bookmarks?.count { !it.trashed } ?: 0,
            contacts = m?.contacts?.count { !it.trashed } ?: 0,
            files = m?.files?.count { !it.trashed } ?: 0,
            photos = gal?.manifest?.photos?.count { !it.trashed } ?: 0,
            vault = pw?.manifest?.secrets?.count { !it.isTrashed } ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Counts())

    /** (used, quota) bytes, or null while loading / on failure. */
    private val _usage = MutableStateFlow<Pair<Long, Long>?>(null)
    val usage: StateFlow<Pair<Long, Long>?> = _usage.asStateFlow()

    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _usage.value = withContext(Dispatchers.IO) { filesUsage.invoke() } }
        viewModelScope.launch { _userName.value = account.me()?.name }
    }
}
