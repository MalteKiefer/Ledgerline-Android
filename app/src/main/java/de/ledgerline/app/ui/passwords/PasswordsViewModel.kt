package de.ledgerline.app.ui.passwords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.ui.graphics.ImageBitmap
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.autofill.DomainMatch
import de.ledgerline.app.core.passwords.Favicons
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.PasswordsRepository
import de.ledgerline.app.domain.model.SecretFields
import de.ledgerline.app.domain.model.SecretItem
import de.ledgerline.app.domain.model.SecretVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

data class PasswordsUi(
    val loading: Boolean = true,
    val error: Boolean = false,
    val secrets: List<SecretItem> = emptyList(),
    val trashCount: Int = 0,
)

@HiltViewModel
class PasswordsViewModel @Inject constructor(
    private val repo: PasswordsRepository,
    private val cache: PasswordsCache,
    private val vaultKeyHolder: VaultKeyHolder,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly
    private val _showTrash = MutableStateFlow(false)
    val showTrash: StateFlow<Boolean> = _showTrash
    private val _folderFilter = MutableStateFlow<String?>(null) // null = all folders
    val folderFilter: StateFlow<String?> = _folderFilter
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** The password folders (secretFolders), for the picker + filter. */
    val folders: StateFlow<List<de.ledgerline.app.domain.model.SecretFolder>> =
        cache.value.map { it?.manifest?.secretFolders.orEmpty() }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(PasswordsUi())
    val state: StateFlow<PasswordsUi> = _state

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    // 2fa.directory dataset (host → setup-docs URL), loaded once best-effort.
    private val _tfa = MutableStateFlow<Map<String, String>>(emptyMap())
    // Favicon caches: positive (domain → bitmap) + a "already tried, no icon" set.
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
    private val iconTried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Pull-to-refresh: re-fetch the sealed secrets store. */
    fun reload() {
        viewModelScope.launch {
            _refreshing.value = true
            repo.load()
            _refreshing.value = false
        }
    }

    init {
        // Recompute the visible list whenever the store or any filter changes. Two nested
        // combines because there are more than five filter inputs.
        val filters = combine(_query, _typeFilter, _favoritesOnly, _showTrash, _folderFilter) { q, type, fav, trash, folder ->
            Filters(q, type, fav, trash, folder)
        }
        combine(cache.value, filters) { store, f -> recompute(store, f) }.launchIn(viewModelScope)
        // Load as soon as the vault is unlocked. This VM can init before the unlock
        // completes (passwords is the first tab), when repo.load() would fail with no VK;
        // observing the unlocked flow re-loads the moment the key lands — and fires
        // immediately when the VM is created after unlock (StateFlow replays `true`).
        vaultKeyHolder.unlocked
            .filter { it }
            .onEach {
                if (cache.value.value == null) {
                    if (repo.load() is Outcome.Err) _state.value = _state.value.copy(loading = false, error = true)
                }
            }
            .launchIn(viewModelScope)
        // Best-effort: the "this site offers 2FA" hint stays hidden until this lands.
        viewModelScope.launch { _tfa.value = repo.tfaDirectory() }
    }

    /**
     * The site favicon for [item], or null to fall back to the type icon. Prefers a stored
     * `icon` data-URI; otherwise fetches one for the item's first web domain (cached per domain).
     */
    suspend fun iconFor(item: SecretItem): ImageBitmap? = withContext(Dispatchers.Default) {
        // Runs off the main thread: the network fetch + Base64/Bitmap decode must never block
        // the UI (produceState's producer runs on the Main dispatcher otherwise).
        Favicons.decode(item.icon)?.let { return@withContext it }
        val domain = DomainMatch.hostsOf(item).firstOrNull() ?: return@withContext null
        iconCache[domain]?.let { return@withContext it }
        if (!iconTried.add(domain)) return@withContext null // already fetched, no usable icon
        val bmp = repo.fetchIcon(domain)?.let { Favicons.decode(it) }
        if (bmp != null) iconCache[domain] = bmp
        bmp
    }

    /**
     * For a login with **no** stored TOTP whose site is known to support app 2FA, the setup-docs
     * URL (http/https) from the 2fa.directory dataset — else null. Walks each URL's host and its
     * parent domains, matching the web `_tfaMatch`.
     */
    fun tfaSetupUrl(item: SecretItem): String? {
        if (item.type != "login" || SecretFields.str(item, "totp").isNotBlank()) return null
        val map = _tfa.value
        if (map.isEmpty()) return null
        for (u in SecretFields.urls(item)) {
            var d = DomainMatch.normalizeHost(u) ?: continue
            while (d.contains('.')) {
                map[d]?.let { url -> if (url.startsWith("http://") || url.startsWith("https://")) return url }
                d = d.substringAfter('.')
            }
        }
        return null
    }

    private data class Filters(val q: String, val type: String?, val favOnly: Boolean, val trash: Boolean, val folder: String?)

    private fun recompute(store: de.ledgerline.app.domain.model.SecretsStore?, f: Filters) {
        val all = store?.manifest?.secrets.orEmpty()
        val query = f.q.trim().lowercase()
        val visible = all.asSequence()
            .filter { it.isTrashed == f.trash }
            .filter { f.type == null || it.type == f.type }
            .filter { !f.favOnly || it.favorite }
            .filter { f.folder == null || it.folder == f.folder }
            .filter {
                query.isEmpty() ||
                    it.title.lowercase().contains(query) ||
                    SecretFields.subtitle(it).lowercase().contains(query) ||
                    it.tags.any { t -> t.lowercase().contains(query) }
            }
            .sortedWith(compareByDescending<SecretItem> { it.favorite }.thenBy { it.title.lowercase() })
            .toList()
        _state.value = PasswordsUi(
            loading = false,
            error = false,
            secrets = visible,
            trashCount = all.count { it.isTrashed },
        )
    }

    fun setQuery(q: String) { _query.value = q }
    fun setTypeFilter(t: String?) { _typeFilter.value = t }
    fun setFolderFilter(id: String?) { _folderFilter.value = id }
    fun toggleFavoritesOnly() { _favoritesOnly.value = !_favoritesOnly.value }
    fun setShowTrash(v: Boolean) { _showTrash.value = v }
    fun consumeMessage() { _message.value = null }

    /** Create a new password folder and return its id (via [onDone]); default role "manage". */
    fun createFolder(name: String, onDone: (String?) -> Unit = {}) {
        val id = de.ledgerline.app.core.Ids.newId()
        viewModelScope.launch {
            val res = repo.save { m -> m.copy(secretFolders = m.secretFolders + de.ledgerline.app.domain.model.SecretFolder(id, name)) }
            onDone(if (res is Outcome.Ok) id else null)
        }
    }

    fun secretById(id: String): SecretItem? = cache.value.value?.manifest?.secrets?.firstOrNull { it.id == id }

    /** A blank draft of [type] (not yet persisted). */
    fun draft(type: String) = SecretItem(id = de.ledgerline.app.core.Ids.newId(), type = type, title = "")

    fun toggleFavorite(id: String) = mutate("favorite") { secrets ->
        secrets.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
    }

    fun trash(id: String) = mutate("trash") { secrets ->
        val now = Instant.now().toString()
        secrets.map { if (it.id == id) it.copy(trashed = now) else it }
    }

    fun restore(id: String) = mutate("restore") { secrets ->
        secrets.map { if (it.id == id) it.copy(trashed = null) else it }
    }

    fun deleteForever(id: String) = mutate("delete") { secrets -> secrets.filterNot { it.id == id } }

    /** Insert or update [item], snapshotting the previous version when its content changed. */
    fun upsert(item: SecretItem, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val now = Instant.now().toString()
            val res = repo.save { m ->
                val existing = m.secrets.firstOrNull { it.id == item.id }
                if (existing == null) {
                    m.copy(secrets = m.secrets + item.copy(created = item.created ?: now, updated = now))
                } else {
                    val changed = existing.title != item.title || existing.fields != item.fields || existing.custom != item.custom
                    val versions = if (changed) {
                        (listOf(SecretVersion(now, existing.title, existing.fields, existing.custom)) + existing.versions).take(100)
                    } else existing.versions
                    val merged = item.copy(
                        created = existing.created,
                        updated = if (changed) now else existing.updated,
                        versions = versions,
                    )
                    m.copy(secrets = m.secrets.map { if (it.id == item.id) merged else it })
                }
            }
            if (res is Outcome.Err) _message.value = "save_failed"
            onDone(res is Outcome.Ok)
        }
    }

    /** Restore a past [version]'s content onto [item] (upsert snapshots the current as a new version). */
    fun restoreVersion(item: SecretItem, version: SecretVersion, onDone: (Boolean) -> Unit = {}) =
        upsert(item.copy(title = version.title, fields = version.fields, custom = version.custom), onDone)

    /**
     * HIBP breach check for [item]'s password (opt-in, k-anonymity — only the 5-hex SHA-1 prefix is
     * sent). [onResult] gets the breach count, or null when there is no password / the lookup failed.
     */
    fun checkBreach(item: SecretItem, onResult: (Int?) -> Unit) {
        val pw = SecretFields.str(item, "password")
        if (pw.isBlank()) { onResult(null); return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val hex = de.ledgerline.app.core.passwords.BreachCheck.sha1Hex(pw)
                val range = repo.breachRange(de.ledgerline.app.core.passwords.BreachCheck.prefix(hex))
                range?.let { de.ledgerline.app.core.passwords.BreachCheck.countInRange(it, de.ledgerline.app.core.passwords.BreachCheck.suffix(hex)) }
            }
            onResult(result)
        }
    }

    private fun mutate(tag: String, block: (List<SecretItem>) -> List<SecretItem>) {
        viewModelScope.launch {
            val res = repo.save { m -> m.copy(secrets = block(m.secrets)) }
            if (res is Outcome.Err) _message.value = tag
        }
    }
}
