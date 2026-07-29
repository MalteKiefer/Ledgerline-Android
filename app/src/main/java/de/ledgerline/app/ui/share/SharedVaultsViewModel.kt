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
}
