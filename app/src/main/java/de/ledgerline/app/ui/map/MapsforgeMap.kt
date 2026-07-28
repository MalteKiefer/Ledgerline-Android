package de.ledgerline.app.ui.map

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import de.ledgerline.app.ui.theme.Brand
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.OpenStreetMapMapnik
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File

/**
 * Shared mapsforge map surface. mapsforge renders offline vector `.map` files with its own
 * engine (LGPL, no Google); when no offline map covers the area and the user has opted into
 * online tiles, it falls back to OSM raster tiles via [TileDownloadLayer]. This is the single
 * map surface for the whole app (Explore/Karte/Tracker and, per the migration, the gallery).
 *
 * Online tiles are OSM-policy compliant (descriptive User-Agent, set globally in [LedgerlineApp]).
 * Callers that can hit online tiles must gate the composable behind the opt-in `MapTilesGate`.
 */

/** Imperative handle to move the camera and set overlays without recomposing the whole map. */
class MapsforgeController {
    internal var mapView: MapView? = null
    private var track: Polyline? = null
    private val markers = mutableListOf<Marker>()
    private val arrowMarkers = mutableListOf<Marker>()
    private var clusterLayer: Layer? = null
    private var searchPin: Marker? = null

    /** Recenter (and optionally set zoom, mapsforge zoom bytes ~0..20). */
    fun moveTo(lat: Double, lng: Double, zoom: Byte? = null) {
        val mv = mapView ?: return
        mv.model.mapViewPosition.center = LatLong(lat, lng)
        if (zoom != null) mv.model.mapViewPosition.zoomLevel = zoom
    }

    /** Fit the camera to a bounding box (with a little padding via zoom back-off). */
    fun fitBounds(minLat: Double, minLng: Double, maxLat: Double, maxLng: Double) {
        val mv = mapView ?: return
        val bb = org.mapsforge.core.model.BoundingBox(minLat, minLng, maxLat, maxLng)
        // Fall back to a sensible viewport if the MapView hasn't been measured yet, so an
        // early fit (right after compose) still picks a reasonable zoom.
        val dim = org.mapsforge.core.model.Dimension(
            mv.width.coerceAtLeast(720),
            mv.height.coerceAtLeast(1080),
        )
        val tileSize = mv.model.displayModel.tileSize
        // Back off one level for a little breathing room around the track.
        val fitZoom = org.mapsforge.core.util.LatLongUtils.zoomForBounds(dim, bb, tileSize)
        val zoom = (fitZoom - 1).coerceIn(0, 20).toByte()
        mv.model.mapViewPosition.mapPosition =
            org.mapsforge.core.model.MapPosition(bb.centerPoint, zoom)
    }

    /** Replace the recorded/planned track polyline. Points are (lat, lng) pairs. */
    fun setTrack(points: List<Pair<Double, Double>>) {
        val mv = mapView ?: return
        track?.let { mv.layerManager.layers.remove(it) }
        arrowMarkers.forEach { mv.layerManager.layers.remove(it) }; arrowMarkers.clear()
        if (points.size < 2) { track = null; mv.invalidate(); return }
        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = Brand.accent.toArgb()
            strokeWidth = 12f
            setStyle(Style.STROKE)
        }
        val line = Polyline(paint, AndroidGraphicFactory.INSTANCE)
        points.forEach { (la, ln) -> line.latLongs.add(LatLong(la, ln)) }
        track = line
        mv.layerManager.layers.add(line)
        mv.invalidate()
    }

    /** Rotate the map so [degrees] clockwise is up (0 = north). Pivot = view center. */
    fun setRotation(degrees: Float) {
        val mv = mapView ?: return
        mv.rotate(org.mapsforge.core.model.Rotation(degrees, mv.width / 2f, mv.height / 2f))
        mv.invalidate()
    }

    /** Current map bearing in degrees (0 = north). */
    fun rotationDegrees(): Float = mapView?.mapRotation?.degrees ?: 0f

    /** Visible bounds as [minLat, minLng, maxLat, maxLng], or null if not laid out yet. */
    fun visibleBounds(): DoubleArray? {
        val mv = mapView ?: return null
        return runCatching {
            val bb = mv.boundingBox
            doubleArrayOf(bb.minLatitude, bb.minLongitude, bb.maxLatitude, bb.maxLongitude)
        }.getOrNull()
    }

    fun resetNorth() = setRotation(0f)

    /** Attach a photo-clustering overlay (see [PhotoClusterLayer]). Safe no-op if not ready. */
    fun setPhotoClusters(photos: List<PhotoPoint>, pin: org.mapsforge.core.graphics.Bitmap?, bubbleColorArgb: Int, onOpenPhoto: (String) -> Unit) {
        val mv = mapView ?: return
        clusterLayer?.let { mv.layerManager.layers.remove(it) }
        val layer = PhotoClusterLayer(mv, photos, pin, bubbleColorArgb, onOpenPhoto)
        clusterLayer = layer
        mv.layerManager.layers.add(layer)
        mv.invalidate()
    }

    /** A single independent search-result pin (separate from waypoint/cluster markers). */
    fun setSearchPin(lat: Double, lng: Double, bitmap: org.mapsforge.core.graphics.Bitmap?) {
        val mv = mapView
        if (mv == null) return
        searchPin?.let { mv.layerManager.layers.remove(it) }
        // Fall back to a drawn dot if no bitmap was provided, so the position is always visible.
        val bmp = bitmap ?: dotBitmap()
        val m = Marker(LatLong(lat, lng), bmp, 0, -bmp.height / 2)
        searchPin = m
        mv.layerManager.layers.add(m)
        mv.invalidate()
    }

    private fun dotBitmap(): org.mapsforge.core.graphics.Bitmap {
        val size = 44
        val bmp = AndroidGraphicFactory.INSTANCE.createBitmap(size, size)
        val canvas = AndroidGraphicFactory.INSTANCE.createCanvas()
        canvas.setBitmap(bmp)
        val fill = AndroidGraphicFactory.INSTANCE.createPaint().apply { setColor(Brand.accent.toArgb()); setStyle(Style.FILL) }
        canvas.drawCircle(size / 2, size / 2, size / 2 - 4, fill)
        val ring = AndroidGraphicFactory.INSTANCE.createPaint().apply { setColor(0xFFFFFFFF.toInt()); setStyle(Style.STROKE); setStrokeWidth(5f) }
        canvas.drawCircle(size / 2, size / 2, size / 2 - 4, ring)
        return bmp
    }

    /**
     * Draw non-interactive direction-of-travel arrows along the track (each rotated to its local
     * bearing). Cleared with the track. [colorArgb] tints the arrowheads.
     */
    fun setDirectionArrows(arrows: List<de.ledgerline.app.core.explore.TrackArrows.Arrow>, colorArgb: Int) {
        val mv = mapView ?: return
        arrowMarkers.forEach { mv.layerManager.layers.remove(it) }; arrowMarkers.clear()
        arrows.forEach { a ->
            val bmp = arrowBitmap(a.bearingDeg.toFloat(), colorArgb) ?: return@forEach
            val m = Marker(LatLong(a.lat, a.lng), bmp, 0, 0)
            arrowMarkers.add(m)
            mv.layerManager.layers.add(m)
        }
        mv.invalidate()
    }

    /** A small filled triangle pointing up, rotated [bearingDeg] clockwise, as a mapsforge bitmap. */
    private fun arrowBitmap(bearingDeg: Float, colorArgb: Int): org.mapsforge.core.graphics.Bitmap? = runCatching {
        val size = 30
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.save()
        c.rotate(bearingDeg, size / 2f, size / 2f)
        val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(230, 255, 255, 255); style = android.graphics.Paint.Style.FILL
        }
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb; style = android.graphics.Paint.Style.FILL
        }
        fun triangle(inset: Float) = android.graphics.Path().apply {
            moveTo(size / 2f, 6f - inset)
            lineTo(size / 2f - (7f + inset), size / 2f + 5f + inset)
            lineTo(size / 2f + (7f + inset), size / 2f + 5f + inset)
            close()
        }
        c.drawPath(triangle(1.5f), halo)
        c.drawPath(triangle(0f), fill)
        c.restore()
        org.mapsforge.map.android.graphics.AndroidBitmap(bmp)
    }.getOrNull()

    /** Replace the set of point markers. */
    fun setMarkers(points: List<Pair<Double, Double>>, bitmap: org.mapsforge.core.graphics.Bitmap?) {
        val mv = mapView ?: return
        markers.forEach { mv.layerManager.layers.remove(it) }
        markers.clear()
        if (bitmap == null) { mv.invalidate(); return }
        points.forEach { (la, ln) ->
            val m = Marker(LatLong(la, ln), bitmap, 0, -bitmap.height / 2)
            markers.add(m)
            mv.layerManager.layers.add(m)
        }
        mv.invalidate()
    }
}

@Composable
fun rememberMapsforgeController(): MapsforgeController = remember { MapsforgeController() }

/** Hilt entry point so plain composables can reach the installed offline maps. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MapDeps {
    fun offlineMapStore(): de.ledgerline.app.core.map.OfflineMapStore
}

/** The brand map-pin as a mapsforge bitmap, for [MapsforgeController.setMarkers]. */
@Composable
fun rememberMapPin(): org.mapsforge.core.graphics.Bitmap? {
    val ctx = LocalContext.current
    return remember {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, de.ledgerline.app.R.drawable.ic_map_pin)
        val b = d?.let { runCatching { AndroidGraphicFactory.convertToBitmap(it) }.getOrNull() }
        b
    }
}

/** The set of installed offline `.map` files (refreshed once per composition). */
@Composable
fun rememberInstalledOfflineMaps(): List<File> {
    val ctx = LocalContext.current
    return remember {
        val store = dagger.hilt.android.EntryPointAccessors
            .fromApplication(ctx.applicationContext, MapDeps::class.java)
            .offlineMapStore()
        store.refreshInstalled()
        store.installedFiles()
    }
}

/**
 * A [MapsforgeMap] with the app's privacy policy applied: installed **offline** maps always
 * render; **online** OSM tiles only after the user opts in (the shared map-tiles gate). If
 * there is neither an offline map nor an online opt-in, the opt-in placeholder is shown.
 */
@Composable
fun GatedMapsforgeMap(
    modifier: Modifier = Modifier,
    controller: MapsforgeController = rememberMapsforgeController(),
    initialLat: Double? = null,
    initialLng: Double? = null,
    initialZoom: Byte = 12,
    onMapTap: ((Double, Double) -> Unit)? = null,
    tilesVm: de.ledgerline.app.ui.gallery.MapTilesViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val offline = rememberInstalledOfflineMaps()
    val online by tilesVm.enabled.collectAsStateWithLifecycle()
    if (offline.isNotEmpty() || online) {
        MapsforgeMap(
            modifier = modifier,
            controller = controller,
            offlineMaps = offline,
            onlineEnabled = online,
            initialLat = initialLat,
            initialLng = initialLng,
            initialZoom = initialZoom,
            onMapTap = onMapTap,
        )
    } else {
        de.ledgerline.app.ui.gallery.MapTilesGate(modifier) { /* enabling flips [online]→true */ }
    }
}

@Composable
fun MapsforgeMap(
    modifier: Modifier = Modifier,
    controller: MapsforgeController = rememberMapsforgeController(),
    offlineMaps: List<File> = emptyList(),
    onlineEnabled: Boolean = false,
    initialLat: Double? = null,
    initialLng: Double? = null,
    initialZoom: Byte = 12,
    dark: Boolean = isSystemInDarkTheme(),
    hillshading: Boolean = false,
    demFolder: File? = null,
    demVersion: Int = 0,
    onMapTap: ((Double, Double) -> Unit)? = null,
    onCenterChanged: ((Double, Double) -> Unit)? = null,
) {
    val context = LocalContext.current

    // Build the MapView once; layer set is (re)configured in the update lambda.
    val mapView = remember {
        MapView(context).also { mv ->
            mv.isClickable = true
            // While a drag/pinch is in progress on the map, tell ancestor gesture handlers (the
            // navigation drawer's horizontal swipe, any scroll container) NOT to intercept — else a
            // sideways pan is stolen and opens the drawer / stutters instead of moving the map. The
            // listener returns false so mapsforge's own onTouchEvent still runs the pan/zoom.
            mv.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            mv.mapScaleBar.isVisible = false
            mv.setBuiltInZoomControls(false)
            // Allow the full zoom range so pinch-out works even past a downloaded region's
            // detail (the world base map / lower zooms then fill in).
            mv.setZoomLevelMin(0)
            mv.setZoomLevelMax(22)
            // Slightly larger labels/symbols so house numbers + POI names read more clearly.
            mv.model.displayModel.setUserScaleFactor(1.25f)
            mv.model.mapViewPosition.zoomLevel = initialZoom
            if (initialLat != null && initialLng != null) {
                mv.model.mapViewPosition.center = LatLong(initialLat, initialLng)
            }
            controller.mapView = mv
        }
    }

    // A single tile cache tied to this MapView's display model.
    val tileCache: TileCache = remember(mapView) {
        AndroidUtil.createTileCache(
            context,
            "ll-mapsforge",
            mapView.model.displayModel.tileSize,
            // Larger cache = smoother pan/zoom (fewer re-renders of vector tiles).
            2.5f,
            mapView.model.frameBufferModel.overdrawFactor,
        )
    }

    // Optional tap layer for pick flows.
    DisposableEffect(mapView, onMapTap) {
        val tapLayer = if (onMapTap != null) {
            object : Layer() {
                override fun draw(
                    boundingBox: org.mapsforge.core.model.BoundingBox,
                    zoomLevel: Byte,
                    canvas: org.mapsforge.core.graphics.Canvas,
                    topLeftPoint: org.mapsforge.core.model.Point,
                    rotation: org.mapsforge.core.model.Rotation,
                ) { /* no-op: interaction only */ }

                override fun onTap(
                    tapLatLong: LatLong,
                    layerXY: org.mapsforge.core.model.Point,
                    tapXY: org.mapsforge.core.model.Point,
                ): Boolean {
                    onMapTap(tapLatLong.latitude, tapLatLong.longitude)
                    return true
                }
            }.also { mapView.layerManager.layers.add(it) }
        } else null
        onDispose { tapLayer?.let { mapView.layerManager.layers.remove(it) } }
    }

    // Configure base tile layers whenever the offline set / online toggle / theme changes.
    DisposableEffect(mapView, offlineMaps, onlineEnabled, dark, hillshading, demVersion) {
        val layers = mapView.layerManager.layers
        // Remove any existing base layers (renderer/download), keep overlays (track/markers/tap).
        val baseLayers = layers.filter { it is TileRendererLayer || it is TileDownloadLayer }
        baseLayers.forEach { layers.remove(it) }

        val existing = offlineMaps.filter { it.exists() && it.length() > 0 }
        var download: TileDownloadLayer? = null
        when {
            existing.isNotEmpty() -> {
                val store: MapDataStore = if (existing.size == 1) {
                    MapFile(existing.first())
                } else {
                    MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).apply {
                        existing.forEach { addMapDataStore(MapFile(it), false, false) }
                    }
                }
                // Terrain relief: attach a hillshading config if enabled and DEM tiles are present.
                val hills = if (hillshading && demFolder != null && (demFolder.listFiles()?.any { it.name.endsWith(".hgt.zip") || it.name.endsWith(".hgt") } == true)) {
                    runCatching {
                        val src = org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource(
                            org.mapsforge.map.layer.hills.DemFolderFS(demFolder),
                            org.mapsforge.map.layer.hills.SimpleShadingAlgorithm(),
                            AndroidGraphicFactory.INSTANCE,
                        )
                        org.mapsforge.map.layer.hills.HillsRenderConfig(src).apply { indexOnThread() }
                    }.getOrNull()
                } else null
                val renderer = if (hills != null) {
                    TileRendererLayer(tileCache, store, mapView.model.mapViewPosition, false, true, false, AndroidGraphicFactory.INSTANCE, hills)
                } else {
                    TileRendererLayer(tileCache, store, mapView.model.mapViewPosition, AndroidGraphicFactory.INSTANCE)
                }
                renderer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
                layers.add(0, renderer)
            }
            onlineEnabled -> {
                val source = OpenStreetMapMapnik.INSTANCE
                source.userAgent = "de.ledgerline.app"
                val dl = TileDownloadLayer(
                    tileCache,
                    mapView.model.mapViewPosition,
                    source,
                    AndroidGraphicFactory.INSTANCE,
                )
                layers.add(0, dl)
                dl.onResume()
                download = dl
            }
        }
        mapView.invalidate()
        onDispose { download?.onPause() }
    }

    // Report camera-center changes (pan/zoom) for e.g. reverse-geocoding the viewport.
    DisposableEffect(mapView, onCenterChanged) {
        val obs = if (onCenterChanged != null) {
            org.mapsforge.map.model.common.Observer {
                val c = mapView.model.mapViewPosition.center
                onCenterChanged(c.latitude, c.longitude)
            }.also { mapView.model.mapViewPosition.addObserver(it) }
        } else null
        onDispose { obs?.let { mapView.model.mapViewPosition.removeObserver(it) } }
    }

    // Tie the MapView to the composition lifecycle; free native resources on dispose.
    DisposableEffect(mapView) {
        onDispose {
            controller.mapView = null
            mapView.destroyAll()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
