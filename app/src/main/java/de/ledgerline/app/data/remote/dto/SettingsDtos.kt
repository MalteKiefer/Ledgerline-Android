package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET/PUT /settings` — per-user non-display settings a mobile client needs beyond the display
 * prefs: the birthday/anniversary notification channels (on the ZK critical path — the client
 * detects a due date and relays via `POST /contacts/notify`, which the server intersects with
 * these) and the personal file version cap. PUT is a partial patch; null fields are left untouched.
 * Channel values ∈ { desktop, ntfy, mail, webhook }; `file_max_versions` ∈ 1..200.
 */
@Serializable
data class UserSettingsDto(
    @SerialName("contact_birthday_channels") val birthdayChannels: List<String>? = null,
    @SerialName("contact_anniversary_channels") val anniversaryChannels: List<String>? = null,
    @SerialName("file_max_versions") val fileMaxVersions: Int? = null,
)
