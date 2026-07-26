package de.ledgerline.app.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import de.ledgerline.app.R
import org.maplibre.android.maps.Style

/**
 * Shared MapLibre helpers for the gallery maps (replaces the old osmdroid setup).
 *
 * We keep the exact same map DATA source as osmdroid did: OpenStreetMap raster tiles
 * from tile.openstreetmap.org, served through an inline raster style JSON (no vector
 * tiles, no third-party tile host). The descriptive User-Agent that the OSM tile usage
 * policy requires is set once, app-wide, on MapLibre's HTTP stack in [LedgerlineApp]
 * (mirroring osmdroid's `Configuration.userAgentValue = packageName`).
 */

/** Style ID under which the shared marker-pin icon is registered on each map style. */
const val MARKER_ICON_ID = "ll-marker-pin"

/**
 * Inline MapLibre style JSON: a single OSM raster source + one raster layer.
 *
 * - Standard OSM tiles at 256px, exactly the tiles osmdroid's `MAPNIK` source used.
 * - `attribution` is required by the OSM tile policy and shown by MapLibre's attribution
 *   control.
 * - No vector tiles, no glyph/sprite endpoints (raster-only needs none), no telemetry.
 */
val OSM_RASTER_STYLE_JSON: String = """
{
  "version": 8,
  "name": "OSM Raster",
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "minzoom": 0,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-tiles",
      "type": "raster",
      "source": "osm",
      "minzoom": 0,
      "maxzoom": 22
    }
  ]
}
""".trimIndent()

/**
 * Register the marker-pin icon on [style] so [org.maplibre.android.plugins.annotation.SymbolManager]
 * symbols can reference it by [MARKER_ICON_ID]. The pin is a vector drawable rendered to a
 * bitmap (there is no old osmdroid `Marker` API in MapLibre).
 */
fun Style.addMarkerIcon(context: Context) {
    if (getImage(MARKER_ICON_ID) != null) return
    // Bundled vector; if it somehow fails to load, skip the pin rather than crash (L5).
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_map_pin) ?: return
    val bitmap: Bitmap = drawable.toBitmap(
        width = drawable.intrinsicWidth,
        height = drawable.intrinsicHeight,
        config = Bitmap.Config.ARGB_8888,
    )
    addImage(MARKER_ICON_ID, bitmap)
}
