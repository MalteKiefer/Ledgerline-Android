package de.ledgerline.app.core.health

import de.ledgerline.app.domain.model.HealthEntry
import de.ledgerline.app.domain.model.HealthUnits
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthComputeTest {

    private fun e(id: String, metric: String, v: Double, ts: String, v2: Double? = null) =
        HealthEntry(id = id, ts = ts, metric = metric, v = v, v2 = v2)

    private val entries = listOf(
        e("1", "weight", 80.0, "2026-07-20T08:00:00Z"),
        e("2", "weight", 79.5, "2026-07-25T08:00:00Z"),
        e("3", "weight", 81.0, "2026-07-10T08:00:00Z"),
        e("4", "bp", 130.0, "2026-07-25T08:00:00Z", v2 = 85.0),
    )

    @Test fun entriesFor_is_metric_filtered_newest_first() {
        val w = HealthCompute.entriesFor(entries, "weight")
        assertEquals(listOf("2", "1", "3"), w.map { it.id })
    }

    @Test fun stats_single_metric() {
        val s = HealthCompute.stats(entries, "weight", HealthUnits())
        assertEquals("79.5", s.latest)
        assertEquals("80.2", s.avg) // (80+79.5+81)/3 = 80.1666 → 80.2
        assertEquals("79.5", s.min)
        assertEquals("81", s.max)
    }

    @Test fun stats_weight_in_lb() {
        val s = HealthCompute.stats(entries, "weight", HealthUnits(weight = "lb"))
        assertEquals("175.3", s.latest) // 79.5 kg → 175.267 → 175.3
    }

    @Test fun bp_display_and_stats() {
        assertEquals("130/85", HealthCompute.displayValue("bp", 130.0, 85.0, HealthUnits()))
        val s = HealthCompute.stats(entries, "bp", HealthUnits())
        assertEquals("130/85", s.latest)
    }

    @Test fun entriesInRange_ascending_and_windowed() {
        val nowMs = java.time.Instant.parse("2026-07-27T00:00:00Z").toEpochMilli()
        val r = HealthCompute.entriesInRange(entries, "weight", "7d", nowMs)
        // Only the 2026-07-20 and 2026-07-25 weights fall inside 7 days, ascending.
        assertEquals(listOf("1", "2"), r.map { it.id })
    }

    @Test fun csv_escapes_and_orders() {
        val withComma = listOf(e("1", "weight", 80.0, "2026-07-20T08:30:00Z").copy(note = "a,b"))
        val csv = HealthCompute.csv(withComma, "weight", HealthUnits())
        val lines = csv.split("\r\n")
        assertEquals("date,time,value,value2,unit,note", lines[0])
        assertEquals("2026-07-20,08:30:00,80,,kg,\"a,b\"", lines[1])
    }

    @Test fun csvCell_quotes_when_needed() {
        assertEquals("plain", HealthCompute.csvCell("plain"))
        assertEquals("\"a,b\"", HealthCompute.csvCell("a,b"))
        assertEquals("\"a\"\"b\"", HealthCompute.csvCell("a\"b"))
    }
}
