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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

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
        // Recompute the visible list whenever the store or any filter changes.
        combine(cache.value, _query, _typeFilter, _favoritesOnly, _showTrash) { store, q, type, favOnly, trash ->
            recompute(store, q, type, favOnly, trash)
        }.launchIn(viewModelScope)
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

    private fun recompute(store: de.ledgerline.app.domain.model.SecretsStore?, q: String, type: String?, favOnly: Boolean, trash: Boolean) {
        val all = store?.manifest?.secrets.orEmpty()
        val query = q.trim().lowercase()
        val visible = all.asSequence()
            .filter { it.isTrashed == trash }
            .filter { type == null || it.type == type }
            .filter { !favOnly || it.favorite }
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
    fun toggleFavoritesOnly() { _favoritesOnly.value = !_favoritesOnly.value }
    fun setShowTrash(v: Boolean) { _showTrash.value = v }
    fun consumeMessage() { _message.value = null }

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

    private fun mutate(tag: String, block: (List<SecretItem>) -> List<SecretItem>) {
        viewModelScope.launch {
            val res = repo.save { m -> m.copy(secrets = block(m.secrets)) }
            if (res is Outcome.Err) _message.value = tag
        }
    }
}
