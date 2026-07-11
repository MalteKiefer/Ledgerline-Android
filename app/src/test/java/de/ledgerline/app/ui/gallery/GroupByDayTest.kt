package de.ledgerline.app.ui.gallery

import de.ledgerline.app.domain.model.GalleryPhoto
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [GalleryViewModel.groupByDay] — the pure timeline grouping logic. */
class GroupByDayTest {

    private fun photo(id: String, taken: String? = null, created: String? = null) =
        GalleryPhoto(id = id, taken_at = taken, created = created)

    @Test fun groups_across_three_days_newest_day_first() {
        // Input is already sorted newest-first (as recompute() delivers it).
        val photos = listOf(
            photo("c1", created = "2026-03-03T12:00:00Z"),
            photo("b1", created = "2026-02-02T12:00:00Z"),
            photo("a1", created = "2026-01-01T12:00:00Z"),
        )
        val groups = GalleryViewModel.groupByDay(photos)
        assertEquals(listOf("2026-03-03", "2026-02-02", "2026-01-01"), groups.map { it.dayKey })
        assertEquals(listOf("c1", "b1", "a1"), groups.map { it.photos.single().id })
    }

    @Test fun same_day_photos_stay_in_one_group_in_order() {
        val photos = listOf(
            photo("x", created = "2026-05-10T18:00:00Z"),
            photo("y", created = "2026-05-10T09:00:00Z"),
            photo("z", created = "2026-05-10T01:00:00Z"),
        )
        val groups = GalleryViewModel.groupByDay(photos)
        assertEquals(1, groups.size)
        assertEquals("2026-05-10", groups[0].dayKey)
        assertEquals(listOf("x", "y", "z"), groups[0].photos.map { it.id })
    }

    @Test fun blank_or_missing_date_goes_to_unknown_group_placed_last() {
        // recompute() sorts unknown (epoch 0) to the end, so it arrives last here.
        val photos = listOf(
            photo("dated", created = "2026-04-04T10:00:00Z"),
            photo("blank", created = "   "),
            photo("missing", created = null),
        )
        val groups = GalleryViewModel.groupByDay(photos)
        assertEquals(listOf("2026-04-04", "unknown"), groups.map { it.dayKey })
        val unknown = groups.last()
        assertEquals("—", unknown.label)
        assertEquals(listOf("blank", "missing"), unknown.photos.map { it.id })
    }

    @Test fun taken_at_preferred_over_created_for_day_key() {
        // Photo taken on the 1st but uploaded (created) on the 2nd → grouped under the 1st.
        val photos = listOf(
            photo("p", taken = "2026-06-01T23:00:00Z", created = "2026-06-02T05:00:00Z"),
        )
        val groups = GalleryViewModel.groupByDay(photos)
        assertEquals(listOf("2026-06-01"), groups.map { it.dayKey })
    }

    @Test fun exif_colon_date_format_normalised_to_iso_day_key() {
        val photos = listOf(photo("e", taken = "2026:07:11 14:30:00"))
        val groups = GalleryViewModel.groupByDay(photos)
        assertEquals(listOf("2026-07-11"), groups.map { it.dayKey })
    }
}
