package de.ledgerline.app.core.health

import de.ledgerline.app.domain.model.HealthEntry
import de.ledgerline.app.domain.model.HealthUnits
import kotlin.math.roundToInt

/**
 * Pure display/stat/export helpers for the Health UI — a port of the display logic in the web
 * `health.js` (`_displaySingle`/`_displayValue`/`entriesFor`/`avgFor`/`csvRows`). Kept separate
 * from the ViewModel so it is unit-testable without Android.
 */
object HealthCompute {

    /** Entries of one metric, newest first (descending by ts). */
    fun entriesFor(entries: List<HealthEntry>, key: String): List<HealthEntry> =
        entries.filter { it.metric == key }.sortedByDescending { it.ts }

    /** Latest entry of a metric, or null. */
    fun latest(entries: List<HealthEntry>, key: String): HealthEntry? = entriesFor(entries, key).firstOrNull()

    /**
     * Entries of a metric within [range] (`7d|30d|90d|1y|all`), ASCENDING by ts (oldest first —
     * for the chart's x axis). [nowMs] is the cutoff anchor.
     */
    fun entriesInRange(entries: List<HealthEntry>, key: String, range: String, nowMs: Long): List<HealthEntry> {
        val all = entriesFor(entries, key)
        if (all.isEmpty()) return emptyList()
        val cutoff = when (range) {
            "all" -> null
            else -> {
                val days = when (range) { "7d" -> 7; "30d" -> 30; "90d" -> 90; "1y" -> 365; else -> 90 }
                nowMs - days * 86_400_000L
            }
        }
        val filtered = if (cutoff != null) all.filter { parseMs(it.ts) >= cutoff } else all
        return filtered.reversed() // ascending
    }

    /** Convert a single canonical value to display units, rounded to 1 dp (like the web). */
    fun displaySingle(key: String, v: Double, units: HealthUnits): Double = when {
        key == "weight" && units.weight == "lb" -> HealthMetrics.kgToLb(v)
        key == "temp" && units.temp == "f" -> HealthMetrics.cToF(v)
        key == "glucose" && units.glucose == "mmoll" -> HealthMetrics.mgdlToMmoll(v)
        else -> HealthMetrics.round1(v)
    }

    /** Convert a canonical pair to a display string (`sys/dia` for bp, single otherwise). */
    fun displayValue(key: String, v: Double, v2: Double?, units: HealthUnits): String =
        if (key == "bp") "${fmt(v)}/${v2?.let { fmt(it) } ?: "?"}" else fmt(displaySingle(key, v, units))

    /** Display unit label for a metric under the current units. */
    fun unitLabel(key: String, units: HealthUnits): String = when (key) {
        "weight" -> if (units.weight == "lb") "lb" else "kg"
        "temp" -> if (units.temp == "f") "°F" else "°C"
        "glucose" -> if (units.glucose == "mmoll") "mmol/L" else "mg/dL"
        else -> HealthMetrics.metric(key)?.unit ?: ""
    }

    data class Stats(val latest: String?, val avg: String?, val min: String?, val max: String?)

    /** Latest / average / min / max for a metric (bp aggregates both series). */
    fun stats(entries: List<HealthEntry>, key: String, units: HealthUnits): Stats {
        val list = entriesFor(entries, key)
        if (list.isEmpty()) return Stats(null, null, null, null)
        return if (key == "bp") {
            val sys = list.map { it.v }
            val dia = list.mapNotNull { it.v2 }
            Stats(
                latest = displayValue(key, list.first().v, list.first().v2, units),
                avg = displayValue(key, mean(sys).roundToDouble(), if (dia.isNotEmpty()) mean(dia).roundToDouble() else null, units),
                min = displayValue(key, sys.min(), if (dia.isNotEmpty()) dia.min() else null, units),
                max = displayValue(key, sys.max(), if (dia.isNotEmpty()) dia.max() else null, units),
            )
        } else {
            val vs = list.map { it.v }
            Stats(
                latest = displayValue(key, list.first().v, null, units),
                avg = fmt(displaySingle(key, vs.average(), units)),
                min = fmt(displaySingle(key, vs.min(), units)),
                max = fmt(displaySingle(key, vs.max(), units)),
            )
        }
    }

    fun classifyLatest(entries: List<HealthEntry>, key: String): HealthMetrics.Status =
        latest(entries, key)?.let { HealthMetrics.classify(key, it.v, it.v2) } ?: HealthMetrics.Status.OK

    // ---- CSV export (RFC-4180, byte-shape of the web csvRows/csvCell) ------

    /** CSV text for one metric: header + rows sorted ascending by ts, values display-converted. */
    fun csv(entries: List<HealthEntry>, key: String, units: HealthUnits): String {
        val header = listOf("date", "time", "value", "value2", "unit", "note")
        val rows = entries.filter { it.metric == key }.sortedBy { it.ts }.map { e ->
            val date = e.ts.take(10)
            val time = if (e.ts.length >= 19) e.ts.substring(11, 19) else ""
            var displayV = e.v
            var unit = HealthMetrics.metric(key)?.unit ?: ""
            when (key) {
                "weight" -> if (units.weight == "lb") { displayV = HealthMetrics.kgToLb(e.v); unit = "lb" }
                "temp" -> if (units.temp == "f") { displayV = HealthMetrics.cToF(e.v); unit = "°F" }
                "glucose" -> if (units.glucose == "mmoll") { displayV = HealthMetrics.mgdlToMmoll(e.v); unit = "mmol/L" }
            }
            listOf(date, time, fmt(displayV), e.v2?.let { fmt(it) } ?: "", unit, e.note)
        }
        return (listOf(header) + rows).joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } }
    }

    /** RFC-4180 cell escape. */
    fun csvCell(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    // ---- helpers -----------------------------------------------------------

    /** Format a number as the web would: integers without `.0`, else 1-dp. */
    fun fmt(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else HealthMetrics.round1(d).toString()

    private fun mean(xs: List<Double>): Double = xs.sum() / xs.size
    private fun Double.roundToDouble(): Double = this.roundToInt().toDouble()

    private fun parseMs(iso: String): Long =
        try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0L }
}
