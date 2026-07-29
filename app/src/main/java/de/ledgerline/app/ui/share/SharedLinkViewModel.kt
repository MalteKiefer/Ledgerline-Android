package de.ledgerline.app.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.ParsedShareLink
import de.ledgerline.app.data.SharedFile
import de.ledgerline.app.data.SharedLinkRepository
import de.ledgerline.app.data.SharedManifest
import de.ledgerline.app.data.SharedPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Recipient-side viewer for a `{baseUrl}/s/{token}#s:{sk}` public share link. */
@HiltViewModel
class SharedLinkViewModel @Inject constructor(
    private val repo: SharedLinkRepository,
) : ViewModel() {

    enum class ErrorKind { BAD_LINK, WRONG_HOST, NOT_FOUND, EXPIRED, GENERIC }

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class NeedsPassword(val link: ParsedShareLink, val name: String?, val wrong: Boolean = false) : State
        data class Ready(val link: ParsedShareLink, val manifest: SharedManifest, val grant: String?) : State
        data class Failed(val kind: ErrorKind) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun reset() { _state.value = State.Idle }

    /** Entry point: a pasted or deep-linked share URL. */
    fun open(url: String) {
        val link = repo.parse(url)
        if (link == null) { _state.value = State.Failed(ErrorKind.BAD_LINK); return }
        if (!repo.isOwnServer(link.host)) { _state.value = State.Failed(ErrorKind.WRONG_HOST); return }
        _state.value = State.Loading
        viewModelScope.launch {
            val meta = repo.meta(link.token)
            when {
                meta == null || !meta.found -> _state.value = State.Failed(ErrorKind.NOT_FOUND)
                meta.expired -> _state.value = State.Failed(ErrorKind.EXPIRED)
                meta.needs_password -> _state.value = State.NeedsPassword(link, meta.name)
                else -> loadManifest(link, null)
            }
        }
    }

    fun submitPassword(link: ParsedShareLink, password: String) {
        _state.value = State.Loading
        viewModelScope.launch {
            val grant = repo.unlock(link.token, password)
            if (grant == null) _state.value = State.NeedsPassword(link, null, wrong = true)
            else loadManifest(link, grant)
        }
    }

    private suspend fun loadManifest(link: ParsedShareLink, grant: String?) {
        val m = repo.manifest(link, grant)
        _state.value = if (m == null) State.Failed(ErrorKind.GENERIC) else State.Ready(link, m, grant)
    }

    private val ready get() = _state.value as? State.Ready

    /** Decrypt a photo's display rendition (medium→thumb) for the in-app grid. */
    suspend fun photoBytes(p: SharedPhoto): ByteArray? {
        val r = ready ?: return null
        val ref = p.displayRef ?: return null
        val key = p.displayKey ?: return null
        return repo.downloadBlob(r.link.token, ref, key, r.link.shareKey, r.grant)
    }

    /** Decrypt a file's full bytes for saving via the Storage Access Framework. */
    suspend fun fileBytes(f: SharedFile): ByteArray? {
        val r = ready ?: return null
        return repo.downloadBlob(r.link.token, f.ref, f.key, r.link.shareKey, r.grant)
    }
}
