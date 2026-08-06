package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable

/**
 * A record-shard descriptor in a Store-v3 sealed root (module-agnostic). Each shard is a
 * separate encrypted blob (`/{module}/raw/{ref}`, decrypted with the wrapped key [key]) holding
 * a bucket of records. Used by Files/Finance/[ShardRoot]/[FilesRoot] and the generic sharded-
 * store engine.
 */
@Serializable
data class GalleryShard(
    val ref: String = "",
    val key: String = "",
    val hash: String? = null,
    val count: Int = 0,
    val bucket: Int = 0,
)

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
