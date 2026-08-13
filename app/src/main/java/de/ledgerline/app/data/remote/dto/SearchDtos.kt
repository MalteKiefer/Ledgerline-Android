package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cross-module global search (`GET /api/v1/search?q=`). Owner-scoped and module-gated — only the
 * modules the account has enabled are searched. Results arrive grouped by [module]; this app only
 * surfaces the in-scope groups (files, notes, finance), the rest are ignored by the UI.
 */
@Serializable
data class GlobalSearchResponse(
    val q: String = "",
    val groups: List<SearchGroup> = emptyList(),
)

@Serializable
data class SearchGroup(
    val module: String = "", // files | notes | gallery | contacts | mail | calendar | finance
    val items: List<SearchHit> = emptyList(),
)

@Serializable
data class SearchHit(
    val id: Int = 0,
    val title: String = "",
    val subtitle: String? = null,
)

/** `POST /api/v1/me/reindex` — queue a re-extraction of the caller's file text/OCR. */
@Serializable
data class ReindexResponse(
    @SerialName("queued") val queued: Boolean = false,
)
