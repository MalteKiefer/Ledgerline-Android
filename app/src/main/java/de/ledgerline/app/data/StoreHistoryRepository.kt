package de.ledgerline.app.data

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.StoreHistoryEntry
import de.ledgerline.app.data.remote.dto.StoreHistoryVersion
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to the server's retained sealed-root version history (recovery net, v1.536). Lists
 * the retained versions of a store and fetches a chosen version's ciphertext; the per-store repo then
 * decrypts + re-merges lost records. The list carries NO ciphertext (version/bytes/date only).
 */
@Singleton
class StoreHistoryRepository(
    private val sessionHolder: SessionHolder,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder) : this(
        sessionHolder,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    enum class Store { FILES, GALLERY, NOTES, PASSWORDS, INVOICES, CONTACTS }

    /** Retained versions of [store], newest first. Empty on failure. */
    suspend fun list(store: Store): List<StoreHistoryEntry> = withContext(Dispatchers.IO) {
        val api = apiProvider(sessionHolder.get() ?: return@withContext emptyList())
        val res: Response<*> = runCatching {
            when (store) {
                Store.FILES -> api.filesStoreHistory()
                Store.GALLERY -> api.galleryStoreHistory()
                Store.NOTES -> api.notesStoreHistory()
                Store.PASSWORDS -> api.passwordsStoreHistory()
                Store.INVOICES -> api.invoicesStoreHistory()
                Store.CONTACTS -> api.contactsStoreHistory()
            }
        }.getOrNull() ?: return@withContext emptyList()
        (res.body() as? de.ledgerline.app.data.remote.dto.StoreHistoryResponse)?.versions.orEmpty()
    }

    /** The ciphertext of one retained version, or null. */
    suspend fun fetch(store: Store, version: Int): StoreHistoryVersion? = withContext(Dispatchers.IO) {
        val api = apiProvider(sessionHolder.get() ?: return@withContext null)
        runCatching {
            when (store) {
                Store.FILES -> api.filesStoreHistoryVersion(version)
                Store.GALLERY -> api.galleryStoreHistoryVersion(version)
                Store.NOTES -> api.notesStoreHistoryVersion(version)
                Store.PASSWORDS -> api.passwordsStoreHistoryVersion(version)
                Store.INVOICES -> api.invoicesStoreHistoryVersion(version)
                Store.CONTACTS -> api.contactsStoreHistoryVersion(version)
            }.takeIf { it.isSuccessful }?.body()
        }.getOrNull()
    }
}
