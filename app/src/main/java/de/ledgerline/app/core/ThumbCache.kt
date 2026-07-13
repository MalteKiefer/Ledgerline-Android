package de.ledgerline.app.core

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory LRU of decrypted thumbnails (photoId -> Bitmap). Never persisted; cleared on lock. */
@Singleton
class ThumbCache @Inject constructor() {
    // Thumbnails are RGB_565 (see GalleryViewModel.thumb) → ~half the bytes of ARGB, so a
    // larger LRU fits safely and fewer evicted cells re-decode from the disk cache on scroll.
    private val max = 512
    private val map = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > max
    }
    @Synchronized fun get(id: String): Bitmap? = map[id]
    @Synchronized fun put(id: String, bmp: Bitmap) { map[id] = bmp }
    @Synchronized fun clear() { map.clear() }
}
