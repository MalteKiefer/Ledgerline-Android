package de.ledgerline.app.core.map

import kotlinx.serialization.Serializable

/**
 * A downloadable offline-map region. mapsforge `.map` files are hosted per region on the
 * public mapsforge download server; the catalog ([assets/map-regions.json]) is a tree of
 * continents → countries. A leaf region (non-empty [path]) is individually downloadable; a
 * branch (null [path], non-empty [children]) only groups leaves.
 */
@Serializable
data class OfflineMapRegion(
    val id: String,
    val name: String,
    /** ISO 3166-1 alpha-2 country code, if this region is a country — used to localize its
     *  display name into the device language. Null for continents / sub-regions. */
    val code: String? = null,
    /** Path under the catalog base URL, e.g. "europe/germany.map". Null for a group node. */
    val path: String? = null,
    /** Approximate download size in MiB (display only; the real size is read on download). */
    val approxSizeMb: Int = 0,
    val children: List<OfflineMapRegion> = emptyList(),
) {
    val isLeaf: Boolean get() = path != null
}

/** Root of the bundled catalog. */
@Serializable
data class OfflineMapCatalog(
    val baseUrl: String,
    val regions: List<OfflineMapRegion>,
) {
    /** Flattened list of every downloadable leaf region. */
    fun leaves(): List<OfflineMapRegion> = buildList { fun walk(r: OfflineMapRegion) { if (r.isLeaf) add(r); r.children.forEach(::walk) }; regions.forEach(::walk) }
}
