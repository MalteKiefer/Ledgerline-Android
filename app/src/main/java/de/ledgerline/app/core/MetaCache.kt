package de.ledgerline.app.core

import de.ledgerline.app.domain.model.PhotoMetaBlob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-only, in-memory cache mapping photoId -> decrypted meta blob so each
 * sealed meta blob is fetched + decrypted at most once per session. Mirrors the
 * web `metaCache` closure.
 *
 * SECURITY: holds plaintext CLIP embeddings + face data. Never persist, never log.
 * Wiped on lock/logout (ForceLogoutImpl + MainActivity lifecycle).
 */
@Singleton
class MetaCache @Inject constructor() {
    private val map = java.util.Collections.synchronizedMap(HashMap<String, PhotoMetaBlob?>())
    fun get(id: String): PhotoMetaBlob? = map[id]
    fun has(id: String): Boolean = map.containsKey(id)
    fun put(id: String, meta: PhotoMetaBlob?) { map[id] = meta }
    fun clear() { map.clear() }
}
