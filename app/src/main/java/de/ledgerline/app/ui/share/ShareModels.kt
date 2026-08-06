package de.ledgerline.app.ui.share

import android.net.Uri

/** One item received via ACTION_SEND / ACTION_SEND_MULTIPLE, routed to the Files import. */
data class SharedItem(
    val uri: Uri,
    val mime: String,
    val name: String,
)
