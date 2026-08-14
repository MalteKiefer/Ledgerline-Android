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
    val fn: String? = null,
    val first_name: String? = null,
    val last_name: String? = null,
    val org: String? = null,
) {
    /** Best display name: formatted name, else first+last, else org, else the id. */
    val display: String
        get() = fn?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(first_name, last_name).joinToString(" ").ifBlank { org ?: id }
}

@Serializable data class ContactsDataResponse(val contacts: List<ContactLite> = emptyList())

interface ContactsApi {
    @GET("api/v1/contacts/data")
    suspend fun data(@Query("q") q: String? = null): Response<ContactsDataResponse>
}
