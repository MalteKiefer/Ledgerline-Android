package de.ledgerline.app.data

import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM coverage for the §C1 policy decode/migration and the `maxBytes` computation.
 * The DataStore flows delegate to [SettingsStore.decodeFilesPolicy]/[decodePhotosPolicy],
 * so testing those functions exercises the same migration logic without a DataStore.
 */
class OfflinePolicyDecodeTest {

    @Test fun files_stored_enum_name_wins() {
        assertEquals(FileBlobPolicy.ALL, SettingsStore.decodeFilesPolicy("ALL", null))
        assertEquals(FileBlobPolicy.OFF, SettingsStore.decodeFilesPolicy("OFF", true))
    }

    @Test fun files_unknown_or_empty_string_falls_back_to_on_demand() {
        assertEquals(FileBlobPolicy.ON_DEMAND, SettingsStore.decodeFilesPolicy("BOGUS", false))
        assertEquals(FileBlobPolicy.ON_DEMAND, SettingsStore.decodeFilesPolicy("", false))
    }

    @Test fun files_migrates_legacy_boolean_when_new_key_absent() {
        assertEquals(FileBlobPolicy.ON_DEMAND, SettingsStore.decodeFilesPolicy(null, true))
        assertEquals(FileBlobPolicy.OFF, SettingsStore.decodeFilesPolicy(null, false))
        assertEquals(FileBlobPolicy.ON_DEMAND, SettingsStore.decodeFilesPolicy(null, null))
    }

    @Test fun photos_stored_enum_and_migration() {
        assertEquals(PhotoBlobPolicy.THUMBS, SettingsStore.decodePhotosPolicy("THUMBS", null))
        assertEquals(PhotoBlobPolicy.ON_DEMAND, SettingsStore.decodePhotosPolicy("nope", null))
        assertEquals(PhotoBlobPolicy.ON_DEMAND, SettingsStore.decodePhotosPolicy(null, true))
        assertEquals(PhotoBlobPolicy.OFF, SettingsStore.decodePhotosPolicy(null, false))
        assertEquals(PhotoBlobPolicy.ON_DEMAND, SettingsStore.decodePhotosPolicy(null, null))
    }

    @Test fun maxBytes_is_mb_times_one_mebibyte_and_zero_stays_zero() {
        assertEquals(0L, flags(maxBytes = 0L).maxBytes())
        assertEquals(1024L * 1024L, flags(maxBytes = 1024L * 1024L).maxBytes())
        assertEquals(512L * 1024L * 1024L, flags(maxBytes = 512L * 1024L * 1024L).maxBytes())
    }

    private fun flags(maxBytes: Long): OfflineFlags = FakeOfflineFlags(maxBytes = maxBytes)
}
