package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable

/**
 * The raw `/files/store` root manifest (Store v3 sharded, byte-compatible with the web
 * `makeShardedStore({ prefix:'/files', recordKey:'files', collections:[fileFolders] })`).
 * The web store is **v3-only** — a small pointer table listing id-bucketed record [shards]
 * plus a single content-addressed `fileFolders` collection blob (`foldersRef/foldersKey/
 * foldersHash`). File records live in the shard blobs; folders in the collection blob.
 *
 * Decode-only DTO — the in-memory model is the aggregate [WorkspaceManifest] (files +
 * fileFolders slices). Reuses [GalleryShard] as the (generic) shard descriptor.
 */
@Serializable
data class FilesRoot(
    val v: Int = 3,
    val suite: Int = 1,
    val shardBits: Int = 0,
    val shards: List<GalleryShard> = emptyList(),
    /** v1/legacy inline fallback; web never writes this (v3-only), kept for tolerance. */
    val files: List<FileEntry> = emptyList(),
    val foldersRef: String? = null,
    val foldersKey: String? = null,
    val foldersHash: String? = null,
)
