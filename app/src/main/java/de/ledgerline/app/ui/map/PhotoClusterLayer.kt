package de.ledgerline.app.ui.map

import org.mapsforge.core.graphics.Bitmap
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import kotlin.math.floor
import kotlin.math.min

/** A geotagged photo for the cluster map. */
data class PhotoPoint(val id: String, val lat: Double, val lng: Double)

/**
 * Grid-clustering overlay for the gallery map. mapsforge has no built-in clustering, so this
 * layer projects every photo to world-pixel space at the current zoom, buckets them into a
 * fixed grid, and draws a numbered bubble per multi-photo cell / the brand pin per single
 * photo — recomputed each frame, so panning + zooming re-cluster naturally. Tapping a bubble
 * zooms in; tapping a single pin opens the photo.
 */
class PhotoClusterLayer(
    private val mapView: MapView,
    private val photos: List<PhotoPoint>,
    private val pin: Bitmap?,
    bubbleColorArgb: Int,
    private val onOpenPhoto: (String) -> Unit,
) : Layer() {

    private data class Drawn(val sx: Int, val sy: Int, val count: Int, val lat: Double, val lng: Double, val id: String)
    private var drawn: List<Drawn> = emptyList()

    private val fill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(bubbleColorArgb); setStyle(Style.FILL)
    }
    private val text = AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(0xFFFFFFFF.toInt()); setStyle(Style.FILL); setTextSize(30f)
    }

    override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: Canvas, topLeftPoint: Point, rotation: Rotation) {
        val tileSize = displayModel.tileSize
        val mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize)
        val cell = 130.0
        val w = canvas.width
        val h = canvas.height

        // Accumulate per grid cell: [count, sumSx, sumSy, sumLat, sumLng].
        val cells = HashMap<Long, DoubleArray>()
        val sampleId = HashMap<Long, String>()
        for (p in photos) {
            val wx = MercatorProjection.longitudeToPixelX(p.lng, mapSize)
            val wy = MercatorProjection.latitudeToPixelY(p.lat, mapSize)
            val sx = wx - topLeftPoint.x
            val sy = wy - topLeftPoint.y
            if (sx < -150 || sy < -150 || sx > w + 150 || sy > h + 150) continue
            val gx = floor(wx / cell).toLong()
            val gy = floor(wy / cell).toLong()
            val key = (gx shl 32) xor (gy and 0xffffffffL)
            val acc = cells.getOrPut(key) { DoubleArray(5) }
            acc[0] += 1.0; acc[1] += sx; acc[2] += sy; acc[3] += p.lat; acc[4] += p.lng
            sampleId[key] = p.id
        }

        val out = ArrayList<Drawn>(cells.size)
        for ((key, acc) in cells) {
            val n = acc[0].toInt()
            val cx = (acc[1] / acc[0]).toInt()
            val cy = (acc[2] / acc[0]).toInt()
            val lat = acc[3] / acc[0]
            val lng = acc[4] / acc[0]
            if (n == 1) {
                pin?.let { canvas.drawBitmap(it, cx - it.width / 2, cy - it.height) }
                out.add(Drawn(cx, cy, 1, lat, lng, sampleId[key] ?: ""))
            } else {
                val r = (18 + min(n, 240) / 8).coerceIn(18, 40)
                canvas.drawCircle(cx, cy, r, fill)
                val label = n.toString()
                canvas.drawText(label, cx - label.length * 8, cy + 10, text)
                out.add(Drawn(cx, cy, n, lat, lng, ""))
            }
        }
        drawn = out
    }

    override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
        val tx = tapXY.x
        val ty = tapXY.y
        val hit = drawn.minByOrNull { (it.sx - tx) * (it.sx - tx) + (it.sy - ty) * (it.sy - ty) } ?: return false
        val d2 = (hit.sx - tx) * (hit.sx - tx) + (hit.sy - ty) * (hit.sy - ty)
        if (d2 > 46.0 * 46.0) return false
        return if (hit.count == 1) {
            if (hit.id.isNotEmpty()) onOpenPhoto(hit.id)
            true
        } else {
            val pos = mapView.model.mapViewPosition
            pos.center = LatLong(hit.lat, hit.lng)
            pos.zoomLevel = (pos.zoomLevel + 2).coerceAtMost(20).toByte()
            mapView.invalidate()
            true
        }
    }
}
