package de.ledgerline.app.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Minimal read-only slice of the server Contacts module — just enough to pick a contact when linking
 * a gallery face-recognition person to an address-book contact. This app has no full Contacts module.
 */
@Serializable data class ContactLite(
    val id: String = "",
    val name: String? = null,
) {
    /** Best display name: the server-formatted name, else the id. */
    val display: String get() = name?.takeIf { it.isNotBlank() } ?: id
}

@Serializable data class ContactSuggestResponse(val contacts: List<ContactLite> = emptyList())

interface ContactsApi {
    /** Same suggestion endpoint the web uses when linking a gallery person → `{id, name}`. */
    @GET("api/v1/contacts/suggest")
    suspend fun suggest(@Query("q") q: String): Response<ContactSuggestResponse>
}
