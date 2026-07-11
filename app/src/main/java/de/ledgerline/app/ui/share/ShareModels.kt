package de.ledgerline.app.ui.share

import android.net.Uri

/** Where a shared item is routed: images/videos to the Gallery, everything else to Files. */
enum class ShareTarget { GALLERY, FILES }

/** One item received via ACTION_SEND / ACTION_SEND_MULTIPLE, already classified. */
data class SharedItem(
    val uri: Uri,
    val mime: String,
    val name: String,
    val target: ShareTarget,
)

/** Pure classifier — unit tested. image or video mimes -> Gallery, else Files. */
fun classify(mime: String?): ShareTarget =
    if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
        ShareTarget.GALLERY
    } else {
        ShareTarget.FILES
    }
