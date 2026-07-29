package de.ledgerline.app.core.explore

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Serialises a track to GPX 1.1. Mirrors iOS `Explore/GPXWriter.swift`
 * (namespaces, `creator="Ledgerline"`, one `<trk><trkseg>`, per-point
 * `<trkpt lat lon><ele/><time/></trkpt>`, ISO-8601 UTC time from epoch ms).
 */
object GpxWriter {
    private const val CREATOR = "Ledgerline"

    fun write(name: String, points: List<TrackPoint>): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"").append(escape(CREATOR))
            .append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            out.append("      <trkpt lat=\"").append(num(p.lat))
                .append("\" lon=\"").append(num(p.lng)).append("\">")
            val inner = StringBuilder()
            p.ele?.let { inner.append("<ele>").append(num(it)).append("</ele>") }
            // 0L = timeless (planned routes, GPX/KML without <time>) → omit rather than emit epoch 1970.
            if (p.t != 0L) inner.append("<time>").append(iso(p.t)).append("</time>")
            out.append(inner).append("</trkpt>\n")
        }
        out.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return out.toString()
    }

    /** Shortest round-trippable decimal; drops a trailing ".0" for integral values. */
    private fun num(n: Double): String {
        if (n == Math.floor(n) && !n.isInfinite() && abs(n) < 1e15) return n.toLong().toString()
        return n.toString()
    }

    /** ISO-8601 UTC, seconds precision (e.g. "2024-07-21T22:13:20Z"). */
    private fun iso(ms: Long): String =
        Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
