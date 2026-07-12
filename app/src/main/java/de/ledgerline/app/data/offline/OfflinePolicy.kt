package de.ledgerline.app.data.offline

/**
 * Per-module offline blob-caching policy (Phase 5c), replacing the 5a on/off booleans.
 *
 * The policy shapes both *cache-on-access* (repos cache whatever is viewed unless the
 * policy is [OFF]) and *prefetch* scope: [ALL] prefetches every referenced blob;
 * [ON_DEMAND] caches only what the user opens; [OFF] disables blob caching entirely.
 */
enum class FileBlobPolicy { OFF, ON_DEMAND, ALL }

/**
 * Per-module offline blob-caching policy for the gallery. [THUMBS] adds thumbnail-only
 * prefetch; on-access caching still stores whatever is viewed (only [OFF] disables it).
 */
enum class PhotoBlobPolicy { OFF, THUMBS, ON_DEMAND, ALL }

/**
 * Per-module offline policy for contact avatar blobs. [ALL] prefetches every avatar;
 * [ON_DEMAND] caches only avatars actually viewed; [OFF] disables avatar caching.
 * (Notes/todos/bookmarks/contacts records themselves live in the sealed `/store`
 * manifest, which the master switch always caches — only avatars are separate blobs.)
 */
enum class ContactBlobPolicy { OFF, ON_DEMAND, ALL }
