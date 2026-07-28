package de.ledgerline.app.core.explore

import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * A track parsed from a GPX or KML file.
 *
 * Mirrors the extraction semantics of the web client
 * `resources/js/shared/track-parse.js` (`parseTrack` — GPX + KML legs). Namespace-
 * agnostic: the DOM is walked by *local* tag name so prefixed elements (`gx:Track`,
 * `gx:coord`) match too. Statistics/bbox assembly lives elsewhere
 * (`TrackStatsComputer`); this object only recovers the ordered point list + name.
 */
data class ImportedTrack(
    val name: String,
    val sourceFormat: String,
    val points: List<TrackPoint>,
)

object TrackImport {

    /**
     * Parse GPX or KML text into a track. [filename] picks the format (by extension)
     * and supplies a default name (filename without extension) when the document
     * carries none. Returns `null` when the input is not XML, is an unsupported/binary
     * format (FIT/KMZ), or contains no usable points.
     *
     * Match to web `parseTrack`: GPX `<trkpt lat lon>` (fallback `<rtept>`, then
     * `<wpt>`) with child `<ele>`/`<time>`; KML `<gx:Track>` (`<when>` + `<gx:coord>`
     * `lng lat ele` pairs) then plain `<coordinates>` (`lng,lat,ele` tuples). `t` is
     * epoch milliseconds (0L when the source carries no time — the web `t` is nullable
     * but our `TrackPoint.t` is a non-null `Long`).
     */
    fun parse(text: String, filename: String): ImportedTrack? {
        if (text.isBlank()) return null

        val root = parseXml(text) ?: return null

        val ext = extensionOf(filename)
        val format = when {
            ext == "gpx" -> "gpx"
            ext == "kml" -> "kml"
            root.localTagName().equals("gpx", ignoreCase = true) -> "gpx"
            root.localTagName().equals("kml", ignoreCase = true) -> "kml"
            // Content sniff: a <trkpt>/<rtept>/<wpt> anywhere ⇒ GPX; else KML.
            findFirst(root, "trkpt") != null ||
                findFirst(root, "rtept") != null ||
                findFirst(root, "wpt") != null -> "gpx"
            else -> "kml"
        }

        val name = textOf(findFirst(root, "name")).ifBlank { null }
        val points = when (format) {
            "gpx" -> parseGpxPoints(root)
            else -> parseKmlPoints(root)
        }
        if (points.isEmpty()) return null

        return ImportedTrack(
            name = name ?: baseName(filename),
            sourceFormat = format,
            points = points,
        )
    }

    // --- GPX ---------------------------------------------------------------

    private fun parseGpxPoints(root: Element): List<TrackPoint> {
        // Prefer trkpt; fall back to rtept, then standalone wpt (web parity).
        var raw = findAll(root, "trkpt")
        if (raw.isEmpty()) raw = findAll(root, "rtept")
        if (raw.isEmpty()) raw = findAll(root, "wpt")

        val points = ArrayList<TrackPoint>(raw.size)
        for (pt in raw) {
            val lat = toNum(attrOf(pt, "lat")) ?: continue
            val lng = toNum(attrOf(pt, "lon")) ?: continue
            points.add(
                TrackPoint(
                    lat = lat,
                    lng = lng,
                    ele = toNum(textOf(findFirst(pt, "ele"))),
                    t = parseTimeToMs(textOf(findFirst(pt, "time"))),
                ),
            )
        }
        return points
    }

    // --- KML ---------------------------------------------------------------

    private fun parseKmlPoints(root: Element): List<TrackPoint> {
        val points = ArrayList<TrackPoint>()

        // 1) gx:Track — parallel <when> and <gx:coord>lng lat ele</gx:coord> lists.
        for (trk in findAll(root, "Track")) {
            val whens = findAll(trk, "when").map { textOf(it) }
            val coords = findAll(trk, "coord").map { textOf(it) }
            for (i in coords.indices) {
                val parts = coords[i].trim().split(WHITESPACE)
                if (parts.size < 2) continue
                val lng = parts[0].toDoubleOrNull()?.finiteOrNull() ?: continue
                val lat = parts[1].toDoubleOrNull()?.finiteOrNull() ?: continue
                val ele = parts.getOrNull(2)?.toDoubleOrNull()?.finiteOrNull()
                points.add(
                    TrackPoint(
                        lat = lat,
                        lng = lng,
                        ele = ele,
                        t = parseTimeToMs(whens.getOrNull(i)),
                    ),
                )
            }
        }

        // 2) Plain LineString / Point <coordinates> — "lng,lat,ele" tuples, no time.
        if (points.isEmpty()) {
            for (c in findAll(root, "coordinates")) {
                val raw = textOf(c)
                for (tuple in raw.trim().split(WHITESPACE)) {
                    if (tuple.isBlank()) continue
                    val parts = tuple.split(',')
                    if (parts.size < 2) continue
                    val lng = parts[0].toDoubleOrNull()?.finiteOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull()?.finiteOrNull() ?: continue
                    val ele = parts.getOrNull(2)?.toDoubleOrNull()?.finiteOrNull()
                    points.add(TrackPoint(lat = lat, lng = lng, ele = ele, t = 0L))
                }
            }
        }

        return points
    }

    // --- XML parsing (namespace-agnostic) ----------------------------------

    private fun parseXml(text: String): Element? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // Harden against XXE / entity expansion; tracks never need them.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                // Swallow the SAX parser's default stderr spew on non-XML input
                // (we translate any failure into a null return below).
                setErrorHandler(object : org.xml.sax.ErrorHandler {
                    override fun warning(e: org.xml.sax.SAXParseException) {}
                    override fun error(e: org.xml.sax.SAXParseException) {}
                    override fun fatalError(e: org.xml.sax.SAXParseException) {
                        throw e
                    }
                })
            }
            val doc = builder.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            doc.documentElement
        } catch (_: Exception) {
            null
        }
    }

    /** Local (prefix-stripped) tag name of an element. */
    private fun Element.localTagName(): String = tagName.substringAfterLast(':')

    /** All descendant elements (self excluded) with the given local name. */
    private fun findAll(node: Element, name: String): List<Element> {
        val want = name.lowercase()
        val out = ArrayList<Element>()
        collect(node, want, out)
        return out
    }

    private fun collect(node: Element, want: String, out: MutableList<Element>) {
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                if (el.localTagName().lowercase() == want) out.add(el)
                collect(el, want, out)
            }
        }
    }

    /** First descendant element with the given local name, or null. */
    private fun findFirst(node: Element, name: String): Element? {
        val want = name.lowercase()
        return firstMatch(node, want)
    }

    private fun firstMatch(node: Element, want: String): Element? {
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                if (el.localTagName().lowercase() == want) return el
                firstMatch(el, want)?.let { return it }
            }
        }
        return null
    }

    /** Trimmed text content of a node (null-safe). */
    private fun textOf(node: Element?): String = (node?.textContent ?: "").trim()

    private fun attrOf(node: Element, name: String): String? {
        // Namespace-agnostic attribute lookup by local name.
        val direct = node.getAttribute(name)
        if (direct.isNotEmpty()) return direct
        val attrs = node.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName.substringAfterLast(':').equals(name, ignoreCase = true)) {
                return a.nodeValue
            }
        }
        return null
    }

    // --- Value helpers -----------------------------------------------------

    private fun toNum(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().toDoubleOrNull()?.finiteOrNull()
    }

    private fun Double.finiteOrNull(): Double? = if (isFinite()) this else null

    /** ISO-8601 time → epoch millis, or 0L when absent/unparseable (TrackPoint.t is non-null). */
    private fun parseTimeToMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            Instant.parse(raw.trim()).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun extensionOf(filename: String): String =
        Regex("\\.([A-Za-z0-9]+)\\s*$").find(filename)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun baseName(filename: String): String {
        val slash = filename.replace('\\', '/').substringAfterLast('/')
        val dot = slash.lastIndexOf('.')
        val stem = if (dot > 0) slash.substring(0, dot) else slash
        return stem.ifBlank { "Track" }
    }

    private val WHITESPACE = Regex("\\s+")
}
