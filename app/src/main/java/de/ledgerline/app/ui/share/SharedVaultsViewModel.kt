package de.ledgerline.app.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.SharedVault
import de.ledgerline.app.data.SharedVaultContent
import de.ledgerline.app.data.SharedVaultRepository
import de.ledgerline.app.data.VaultFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Read/accept side of cross-user shared vaults: list, accept invites, open + view contents. */
@HiltViewModel
class SharedVaultsViewModel @Inject constructor(
    private val repo: SharedVaultRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val vaults: List<SharedVault> = emptyList(),
        val busy: Boolean = false,
        val opened: SharedVaultContent? = null,
        val openedVault: SharedVault? = null,
        val openError: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        _state.value = _state.value.copy(loading = false, vaults = repo.list())
    }

    fun accept(v: SharedVault) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        val ok = repo.accept(v.vaultId, v.membershipId)
        _state.value = _state.value.copy(busy = false)
        if (ok) refresh()
    }

    fun open(v: SharedVault) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, openError = false)
        val content = repo.open(v)
        _state.value = _state.value.copy(
            busy = false,
            opened = content,
            openedVault = if (content != null) v else null,
            openError = content == null,
        )
    }

    fun close() { _state.value = _state.value.copy(opened = null, openedVault = null, openError = false) }

    suspend fun fileBytes(f: VaultFile): ByteArray? {
        val v = _state.value.openedVault ?: return null
        return repo.downloadFile(v.vaultId, f)
    }

    // ---- Owner side ----

    private val _members = MutableStateFlow<List<de.ledgerline.app.data.remote.dto.VaultMemberDto>>(emptyList())
    val members: StateFlow<List<de.ledgerline.app.data.remote.dto.VaultMemberDto>> = _members.asStateFlow()

    /** Transient one-shot message key for the UI snackbar (e.g. "invite_ok", "invite_not_found"). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun createVault(kind: String, name: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        val id = repo.create(kind, name)
        _state.value = _state.value.copy(busy = false)
        _message.value = if (id != null) "vault_created" else "vault_create_failed"
        if (id != null) refresh()
    }

    fun loadMembers(v: SharedVault) = viewModelScope.launch { _members.value = repo.members(v.vaultId) }

    fun invite(v: SharedVault, identifier: String, role: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        val res = repo.invite(v, identifier, role)
        _state.value = _state.value.copy(busy = false)
        _message.value = when (res) {
            SharedVaultRepository.InviteResult.OK -> "invite_ok"
            SharedVaultRepository.InviteResult.NOT_FOUND, SharedVaultRepository.InviteResult.NO_RECIPIENT_KEY -> "invite_not_found"
            else -> "invite_failed"
        }
        if (res == SharedVaultRepository.InviteResult.OK) loadMembers(v)
    }

    fun updateRole(v: SharedVault, memberId: Long, role: String) = viewModelScope.launch {
        if (repo.updateMemberRole(v.vaultId, memberId, role)) loadMembers(v)
    }

    fun removeMember(v: SharedVault, memberId: Long) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        val ok = repo.removeMemberAndRotate(v, memberId)
        _state.value = _state.value.copy(busy = false)
        _message.value = if (ok) "member_removed" else "member_remove_failed"
        if (ok) loadMembers(v)
    }
}
