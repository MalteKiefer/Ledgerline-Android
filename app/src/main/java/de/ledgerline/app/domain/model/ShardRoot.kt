package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Generic Store-v3 sharded root (module-agnostic): a pointer table of id-bucketed record
 * [shards] plus one optional content-addressed collection blob (`foldersRef/foldersKey/
 * foldersHash`). Same shape as [FilesRoot] but without a typed inline record list, so it decodes
 * a notes or passwords root as well as a files root.
 */
@Serializable
data class ShardRoot(
    val v: Int = 3,
    val suite: Int = 1,
    val shardBits: Int = 0,
    val shards: List<GalleryShard> = emptyList(),
    val foldersRef: String? = null,
    val foldersKey: String? = null,
    val foldersHash: String? = null,
)
