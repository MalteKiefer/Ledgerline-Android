# Gallery Background Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-back-up selected device albums into the encrypted gallery while the vault is unlocked, reusing the existing `ImportPhotos` pipeline.

**Architecture:** A `GalleryBackupManager` scans selected `MediaStore` albums, skips already-known items via a local `BackupStateStore`, turns the rest into `PhotoSource`s, and runs them through the existing `ImportPhotos` use-case wrapped in an `OperationManager` foreground op (`OpKind.BACKUP`). Runs only while unlocked (VK present); triggered on unlock and manually. No `WorkManager` — the biometric-sealed token forbids headless auth.

**Tech Stack:** Kotlin, Hilt, Coroutines, Jetpack Compose, DataStore, MediaStore, JUnit + MockK + Robolectric.

**Spec:** `docs/superpowers/specs/2026-07-12-gallery-background-backup-design.md`

**Shared types** (defined in Task 2, referenced throughout):
```kotlin
// data/backup/BackupModels.kt
data class DeviceAlbum(val bucketId: String, val name: String, val count: Int, val sampleUri: Uri?)
data class BackupItem(val mediaStoreId: Long, val uri: Uri, val name: String, val mime: String, val sizeBytes: Long, val dateTakenMs: Long)
```

---

### Task 1: Add `OpKind.BACKUP`

**Files:**
- Modify: `app/src/main/java/de/ledgerline/app/core/ops/OperationManager.kt:18`
- Modify: `app/src/main/java/de/ledgerline/app/core/ops/BackgroundOpService.kt:92-96`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`

- [ ] **Step 1: Add the enum value**

`OperationManager.kt:18`:
```kotlin
enum class OpKind { FACE_SCAN, DUPLICATE_SCAN, UPLOAD, BLOB_CLEANUP, PREFETCH, BACKUP }
```

- [ ] **Step 2: Add the label mapping**

`BackgroundOpService.kt`, in the `when` that maps `OpKind` to a string res, add:
```kotlin
        OpKind.BACKUP -> R.string.ops_kind_backup
```

- [ ] **Step 3: Add strings**

`values/strings.xml`:
```xml
    <string name="ops_kind_backup">Backing up photos</string>
```
`values-de/strings.xml`:
```xml
    <string name="ops_kind_backup">Fotos sichern</string>
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL` (a `when` over `OpKind` elsewhere may now warn/err if non-exhaustive — if `compileDebugKotlin` fails on a missing branch, add `OpKind.BACKUP -> ...` there mirroring `PREFETCH`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/ops/ app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml
git commit -m "feat(backup): add OpKind.BACKUP"
```

---

### Task 2: Backup models + `BackupStateStore`

**Files:**
- Create: `app/src/main/java/de/ledgerline/app/data/backup/BackupModels.kt`
- Create: `app/src/main/java/de/ledgerline/app/data/backup/BackupStateStore.kt`
- Test: `app/src/test/java/de/ledgerline/app/data/backup/BackupStateStoreTest.kt`

`BackupStateStore` records which `MediaStore` ids are already backed up (fast-skip; the authoritative dedup is the `sig` check inside `ImportPhotos`). Device-local ids only — no plaintext.

- [ ] **Step 1: Create the shared models**

`BackupModels.kt`:
```kotlin
package de.ledgerline.app.data.backup

import android.net.Uri

/** A device photo album (MediaStore bucket) shown in the picker. */
data class DeviceAlbum(val bucketId: String, val name: String, val count: Int, val sampleUri: Uri?)

/** A candidate device media item to back up. */
data class BackupItem(
    val mediaStoreId: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
)
```

- [ ] **Step 2: Write the failing test**

`BackupStateStoreTest.kt`:
```kotlin
package de.ledgerline.app.data.backup

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupStateStoreTest {
    private fun store() = BackupStateStore(ApplicationProvider.getApplicationContext())

    @Test fun `unmarked id is not contained`() = runTest {
        assertFalse(store().isBackedUp(42L))
    }

    @Test fun `marked ids persist and are contained`() = runTest {
        val s = store()
        s.mark(setOf(1L, 2L, 3L))
        assertTrue(s.isBackedUp(1L))
        assertTrue(s.isBackedUp(3L))
        assertFalse(s.isBackedUp(4L))
    }

    @Test fun `mark is additive`() = runTest {
        val s = store()
        s.mark(setOf(1L))
        s.mark(setOf(2L))
        assertEquals(setOf(1L, 2L), s.backedUpIds())
    }
}
```

- [ ] **Step 3: Run it, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackupStateStoreTest*" --console=plain`
Expected: FAIL — `BackupStateStore` unresolved.

- [ ] **Step 4: Implement `BackupStateStore`**

`BackupStateStore.kt`:
```kotlin
package de.ledgerline.app.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupStateStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerline_backup_state")

/**
 * Device-local set of already-backed-up MediaStore ids — a fast-skip so a run doesn't
 * re-read + re-hash every device photo. Not sensitive (ids are device-local); the real
 * dedup is the sha-256 sig check inside [de.ledgerline.app.domain.usecase.ImportPhotos].
 */
@Singleton
class BackupStateStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringSetPreferencesKey("backed_up_ids")

    suspend fun backedUpIds(): Set<Long> =
        context.backupStateStore.data.first()[key].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    suspend fun isBackedUp(id: Long): Boolean =
        context.backupStateStore.data.first()[key].orEmpty().contains(id.toString())

    suspend fun mark(ids: Set<Long>) {
        if (ids.isEmpty()) return
        context.backupStateStore.edit { prefs ->
            prefs[key] = prefs[key].orEmpty() + ids.map { it.toString() }
        }
    }

    suspend fun clear() {
        context.backupStateStore.edit { it.remove(key) }
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackupStateStoreTest*" --console=plain`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data/backup/ app/src/test/java/de/ledgerline/app/data/backup/
git commit -m "feat(backup): backup models + local BackupStateStore"
```

---

### Task 3: `BackupScanner` (MediaStore query)

**Files:**
- Create: `app/src/main/java/de/ledgerline/app/data/backup/BackupScanner.kt`
- Test: `app/src/test/java/de/ledgerline/app/data/backup/BackupScannerTest.kt`

Queries `MediaStore.Files` for images+videos in the given buckets. `ContentResolver` is injected so tests stub `query(...)` with a `MatrixCursor`.

- [ ] **Step 1: Write the failing test**

`BackupScannerTest.kt`:
```kotlin
package de.ledgerline.app.data.backup

import android.content.ContentResolver
import android.database.MatrixCursor
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupScannerTest {
    private val cols = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_TAKEN,
    )

    private fun cursorOf(vararg rows: Array<Any?>) = MatrixCursor(cols).apply { rows.forEach { addRow(it) } }

    @Test fun `maps rows to BackupItems`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns
            cursorOf(
                arrayOf(7L, "IMG_1.jpg", "image/jpeg", 1234L, 1_000L),
                arrayOf(8L, "VID_2.mp4", "video/mp4", 9999L, 2_000L),
            )

        val items = BackupScanner(resolver).scan(setOf("bucketA"))

        assertEquals(2, items.size)
        assertEquals(7L, items[0].mediaStoreId)
        assertEquals("IMG_1.jpg", items[0].name)
        assertEquals("image/jpeg", items[0].mime)
        assertEquals(1234L, items[0].sizeBytes)
        assertEquals("video/mp4", items[1].mime)
    }

    @Test fun `empty buckets set returns empty without querying`() {
        val resolver = mockk<ContentResolver>()
        assertEquals(emptyList<BackupItem>(), BackupScanner(resolver).scan(emptySet()))
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackupScannerTest*" --console=plain`
Expected: FAIL — `BackupScanner` unresolved.

- [ ] **Step 3: Implement `BackupScanner`**

`BackupScanner.kt`:
```kotlin
package de.ledgerline.app.data.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject

/**
 * Lists candidate device media (images + videos) in the given MediaStore buckets,
 * newest first. Pure mapping over an injected [ContentResolver] so it is unit-testable
 * with a stubbed cursor.
 */
class BackupScanner(private val resolver: ContentResolver) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.contentResolver)

    fun scan(bucketIds: Set<String>): List<BackupItem> {
        if (bucketIds.isEmpty()) return emptyList()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
        )
        val mediaTypeSel =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val bucketSel = bucketIds.joinToString(",") { "?" }
        val selection = "$mediaTypeSel AND ${MediaStore.Files.FileColumns.BUCKET_ID} IN ($bucketSel)"
        val args = bucketIds.toTypedArray()
        val sort = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"

        val items = ArrayList<BackupItem>()
        resolver.query(uri, projection, selection, args, sort)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                items.add(
                    BackupItem(
                        mediaStoreId = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        name = c.getString(nameIdx) ?: "IMG_$id",
                        mime = c.getString(mimeIdx) ?: "application/octet-stream",
                        sizeBytes = c.getLong(sizeIdx),
                        dateTakenMs = c.getLong(dateIdx),
                    ),
                )
            }
        }
        return items
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackupScannerTest*" --console=plain`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data/backup/BackupScanner.kt app/src/test/java/de/ledgerline/app/data/backup/BackupScannerTest.kt
git commit -m "feat(backup): BackupScanner over MediaStore buckets"
```

---

### Task 4: `DeviceAlbums` (bucket list for the picker)

**Files:**
- Create: `app/src/main/java/de/ledgerline/app/data/backup/DeviceAlbums.kt`
- Test: `app/src/test/java/de/ledgerline/app/data/backup/DeviceAlbumsTest.kt`

- [ ] **Step 1: Write the failing test**

`DeviceAlbumsTest.kt`:
```kotlin
package de.ledgerline.app.data.backup

import android.content.ContentResolver
import android.database.MatrixCursor
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceAlbumsTest {
    private val cols = arrayOf(
        MediaStore.Files.FileColumns.BUCKET_ID,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Files.FileColumns._ID,
    )

    @Test fun `aggregates buckets with counts`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(cols).apply {
                addRow(arrayOf<Any?>("b1", "Camera", 10L))
                addRow(arrayOf<Any?>("b1", "Camera", 11L))
                addRow(arrayOf<Any?>("b2", "Screenshots", 20L))
            }

        val albums = DeviceAlbums(resolver).list()

        assertEquals(2, albums.size)
        assertEquals("Camera", albums.first { it.bucketId == "b1" }.name)
        assertEquals(2, albums.first { it.bucketId == "b1" }.count)
        assertEquals(1, albums.first { it.bucketId == "b2" }.count)
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*DeviceAlbumsTest*" --console=plain`
Expected: FAIL — `DeviceAlbums` unresolved.

- [ ] **Step 3: Implement `DeviceAlbums`**

`DeviceAlbums.kt`:
```kotlin
package de.ledgerline.app.data.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Aggregates MediaStore image+video buckets into a [DeviceAlbum] list for the picker. */
class DeviceAlbums(private val resolver: ContentResolver) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.contentResolver)

    fun list(): List<DeviceAlbum> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns._ID,
        )
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val sort = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"

        data class Agg(var name: String, var count: Int, var sampleId: Long)
        val map = LinkedHashMap<String, Agg>()
        resolver.query(uri, projection, selection, null, sort)?.use { c ->
            val bIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val nIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            while (c.moveToNext()) {
                val bucket = c.getString(bIdx) ?: continue
                val name = c.getString(nIdx) ?: bucket
                val id = c.getLong(idIdx)
                val agg = map.getOrPut(bucket) { Agg(name, 0, id) }
                agg.count++
            }
        }
        return map.map { (bucket, agg) ->
            DeviceAlbum(bucket, agg.name, agg.count, ContentUris.withAppendedId(uri, agg.sampleId))
        }
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*DeviceAlbumsTest*" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data/backup/DeviceAlbums.kt app/src/test/java/de/ledgerline/app/data/backup/DeviceAlbumsTest.kt
git commit -m "feat(backup): DeviceAlbums bucket list"
```

---

### Task 5: Backup preferences in `SettingsStore`

**Files:**
- Modify: `app/src/main/java/de/ledgerline/app/data/SettingsStore.kt`

Reuses the existing `prefetchWifiOnly` / `prefetchChargingOnly` constraint prefs — only two new keys here.

- [ ] **Step 1: Add keys + flows + setters**

In `SettingsStore.kt`, add near the other keys:
```kotlin
    private val backupEnabledKey = booleanPreferencesKey("backup_enabled")
    private val backupAlbumsKey = stringSetPreferencesKey("backup_album_ids")
```
(add `import androidx.datastore.preferences.core.stringSetPreferencesKey` if missing)

Add near the other flows:
```kotlin
    /** Master camera-backup switch. Defaults to OFF (opt-in). */
    val backupEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[backupEnabledKey] ?: false }

    suspend fun setBackupEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[backupEnabledKey] = enabled }
    }

    /** MediaStore bucket ids selected for backup. */
    val backupAlbumIds: Flow<Set<String>> =
        context.settingsDataStore.data.map { it[backupAlbumsKey].orEmpty() }

    suspend fun setBackupAlbumIds(ids: Set<String>) {
        context.settingsDataStore.edit { it[backupAlbumsKey] = ids }
    }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data/SettingsStore.kt
git commit -m "feat(backup): backup prefs (enabled + album ids)"
```

---

### Task 6: `GalleryBackupManager` (orchestration)

**Files:**
- Create: `app/src/main/java/de/ledgerline/app/core/backup/GalleryBackupManager.kt`
- Test: `app/src/test/java/de/ledgerline/app/core/backup/GalleryBackupManagerTest.kt`

Gates on unlocked + enabled + albums + constraints, filters known ids, runs `ImportPhotos` under an op, marks done. `ContentResolver` is used only to open bytes for the `PhotoSource.read` lambda.

- [ ] **Step 1: Write the failing test**

`GalleryBackupManagerTest.kt`:
```kotlin
package de.ledgerline.app.core.backup

import android.content.ContentResolver
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.ConstraintChecker
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.backup.BackupItem
import de.ledgerline.app.data.backup.BackupScanner
import de.ledgerline.app.data.backup.BackupStateStore
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.ImportResult
import de.ledgerline.app.domain.usecase.PhotoSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryBackupManagerTest {
    private val session = Session("https://h", "tok", "", null)

    private fun item(id: Long) =
        BackupItem(id, mockk(relaxed = true), "IMG_$id.jpg", "image/jpeg", 100, id)

    private fun manager(
        scanner: BackupScanner,
        importPhotos: ImportPhotos,
        state: BackupStateStore,
        vk: ByteArray? = ByteArray(32),
        enabled: Boolean = true,
        albums: Set<String> = setOf("b1"),
        wifiOk: Boolean = true,
    ): GalleryBackupManager {
        val settings = mockk<SettingsStore>()
        every { settings.backupEnabled } returns kotlinx.coroutines.flow.flowOf(enabled)
        every { settings.backupAlbumIds } returns kotlinx.coroutines.flow.flowOf(albums)
        every { settings.prefetchWifiOnly } returns kotlinx.coroutines.flow.flowOf(true)
        every { settings.prefetchChargingOnly } returns kotlinx.coroutines.flow.flowOf(false)
        val sessions = mockk<SessionHolder>(); every { sessions.get() } returns session
        val vkHolder = mockk<VaultKeyHolder>(); every { vkHolder.get() } returns vk
        val constraints = mockk<ConstraintChecker>()
        every { constraints.wifiConstraintMet(any()) } returns wifiOk
        every { constraints.chargingConstraintMet(any()) } returns true
        val ops = mockk<OperationManager>(relaxed = true)
        // Run the op block inline on the calling coroutine.
        every { ops.run(any(), any(), captureLambda<suspend ((Int, Int) -> Unit) -> Unit>()) } answers {
            val block = lambda<suspend ((Int, Int) -> Unit) -> Unit>().captured
            kotlinx.coroutines.runBlocking { block { _, _ -> } }
            mockk(relaxed = true)
        }
        val resolver = mockk<ContentResolver>(relaxed = true)
        return GalleryBackupManager(
            scanner, importPhotos, state, settings, sessions, vkHolder, constraints, ops, resolver,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test fun `locked vault does nothing`() = runTest {
        val importPhotos = mockk<ImportPhotos>(relaxed = true)
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        manager(scanner, importPhotos, state, vk = null).maybeRun()
        coVerify(exactly = 0) { importPhotos.invoke(any(), any()) }
    }

    @Test fun `disabled does nothing`() = runTest {
        val importPhotos = mockk<ImportPhotos>(relaxed = true)
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        manager(scanner, importPhotos, state, enabled = false).maybeRun()
        coVerify(exactly = 0) { importPhotos.invoke(any(), any()) }
    }

    @Test fun `constraint blocked does nothing`() = runTest {
        val importPhotos = mockk<ImportPhotos>(relaxed = true)
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        manager(scanner, importPhotos, state, wifiOk = false).maybeRun()
        coVerify(exactly = 0) { importPhotos.invoke(any(), any()) }
    }

    @Test fun `uploads only unknown items and marks them`() = runTest {
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1), item(2))
        val state = mockk<BackupStateStore>()
        coEvery { state.backedUpIds() } returns setOf(1L)          // id 1 already done
        val markSlot = slot<Set<Long>>()
        coEvery { state.mark(capture(markSlot)) } returns Unit
        val importPhotos = mockk<ImportPhotos>()
        val srcSlot = slot<List<PhotoSource>>()
        coEvery { importPhotos.invoke(capture(srcSlot), any()) } returns ImportResult(done = 1, failed = 0)

        manager(scanner, importPhotos, state).maybeRun()

        assertEquals(1, srcSlot.captured.size)                     // only id 2 uploaded
        assertEquals(setOf(2L), markSlot.captured)                 // id 2 marked done
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*GalleryBackupManagerTest*" --console=plain`
Expected: FAIL — `GalleryBackupManager` unresolved.

- [ ] **Step 3: Implement `GalleryBackupManager`**

`GalleryBackupManager.kt`:
```kotlin
package de.ledgerline.app.core.backup

import android.content.ContentResolver
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.ConstraintChecker
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.backup.BackupItem
import de.ledgerline.app.data.backup.BackupScanner
import de.ledgerline.app.data.backup.BackupStateStore
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.PhotoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs up selected device albums into the encrypted gallery, reusing [ImportPhotos]
 * (sig-dedup + encrypt + /gallery/process + store write). Runs ONLY while unlocked, via
 * an [OperationManager] foreground op — see the design spec for the zero-knowledge limit.
 */
@Singleton
class GalleryBackupManager @Inject constructor(
    private val scanner: BackupScanner,
    private val importPhotos: ImportPhotos,
    private val state: BackupStateStore,
    private val settings: SettingsStore,
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val constraints: ConstraintChecker,
    private val operationManager: OperationManager,
    private val resolver: ContentResolver,
    @VisibleForTesting private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(
        scanner: BackupScanner,
        importPhotos: ImportPhotos,
        state: BackupStateStore,
        settings: SettingsStore,
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        constraints: ConstraintChecker,
        operationManager: OperationManager,
        @ApplicationContext context: Context,
    ) : this(
        scanner, importPhotos, state, settings, sessionHolder, vaultKeyHolder, constraints,
        operationManager, context.contentResolver, Dispatchers.IO,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)

    /** Trigger a run if all conditions hold. Safe to call on unlock and from a button. */
    fun maybeRun() {
        scope.launch { runOnce() }
    }

    private suspend fun runOnce() {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!settings.backupEnabled.first()) return
            if (vaultKeyHolder.get() == null) return          // locked → can't encrypt
            if (sessionHolder.get() == null) return           // not paired → can't auth
            val albums = settings.backupAlbumIds.first()
            if (albums.isEmpty()) return
            if (!constraints.wifiConstraintMet(settings.prefetchWifiOnly.first())) return
            if (!constraints.chargingConstraintMet(settings.prefetchChargingOnly.first())) return

            val known = state.backedUpIds()
            val candidates = withContext(ioDispatcher) { scanner.scan(albums) }.filter { it.mediaStoreId !in known }
            if (candidates.isEmpty()) return

            val sources = candidates.map { it.toSource() }
            operationManager.run(OpKind.BACKUP, total = sources.size) { report ->
                importPhotos.invoke(sources, report)
            }
            // Mark every candidate we handed to ImportPhotos (done or deduped both count as
            // "backed up"; a genuine failure is retried next run because ImportPhotos does
            // not persist partial state and the sig-dedup makes a retry idempotent).
            state.mark(candidates.map { it.mediaStoreId }.toSet())
        } finally {
            running.set(false)
        }
    }

    private fun BackupItem.toSource() = PhotoSource(
        name = name,
        mime = mime,
        read = { resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0) },
    )
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*GalleryBackupManagerTest*" --console=plain`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/backup/ app/src/test/java/de/ledgerline/app/core/backup/
git commit -m "feat(backup): GalleryBackupManager orchestration"
```

---

### Task 7: Manifest media permissions + welcome-screen card

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/de/ledgerline/app/ui/onboarding/WelcomeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`

- [ ] **Step 1: Add manifest permissions**

In `AndroidManifest.xml`, beside the other `uses-permission` lines:
```xml
    <!-- Optional: read device photos/videos for camera-roll backup into the gallery. -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

- [ ] **Step 2: Add strings**

`values/strings.xml`:
```xml
    <string name="welcome_media_title">Photo backup</string>
    <string name="welcome_media_body">Optional: back up your camera roll into the encrypted gallery. Photos are read only to encrypt and upload them — nothing leaves the app in the clear.</string>
    <string name="welcome_media_allow">Allow photo access</string>
    <string name="welcome_media_granted">Photo access enabled</string>
```
`values-de/strings.xml`:
```xml
    <string name="welcome_media_title">Foto-Backup</string>
    <string name="welcome_media_body">Optional: sichere deine Kamerarolle in die verschlüsselte Galerie. Fotos werden nur zum Verschlüsseln und Hochladen gelesen — nichts verlässt die App im Klartext.</string>
    <string name="welcome_media_allow">Fotozugriff erlauben</string>
    <string name="welcome_media_granted">Fotozugriff aktiviert</string>
```

- [ ] **Step 3: Add a media-permission card**

In `WelcomeScreen.kt`, mirror the existing contacts card. Add state + launcher near the others:
```kotlin
    var mediaGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        mediaGranted = res.values.any { it }
    }
```
Add a `Card` after the contacts card, identical structure, using `Icons.Outlined.PhotoLibrary`, `welcome_media_*` strings, and:
```kotlin
                    OutlinedButton(
                        onClick = {
                            mediaLauncher.launch(
                                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.welcome_media_allow)) }
```
Add `import androidx.compose.material.icons.outlined.PhotoLibrary`.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/de/ledgerline/app/ui/onboarding/WelcomeScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml
git commit -m "feat(backup): media permissions + welcome card"
```

---

### Task 8: Backup settings screen (route, VM, UI)

**Files:**
- Modify: `app/src/main/java/de/ledgerline/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/de/ledgerline/app/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`

- [ ] **Step 1: Expose backup state + actions from `SettingsViewModel`**

Inject into the constructor: `private val backupManager: GalleryBackupManager`, `private val deviceAlbums: DeviceAlbums`. Add:
```kotlin
    val backupEnabled: StateFlow<Boolean> = settingsStore.backupEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val backupAlbumIds: StateFlow<Set<String>> = settingsStore.backupAlbumIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _albums = MutableStateFlow<List<DeviceAlbum>>(emptyList())
    val albums: StateFlow<List<DeviceAlbum>> = _albums.asStateFlow()

    fun loadAlbums() = viewModelScope.launch {
        _albums.value = withContext(Dispatchers.IO) { deviceAlbums.list() }
    }
    fun setBackupEnabled(on: Boolean) = viewModelScope.launch {
        settingsStore.setBackupEnabled(on)
        if (on) backupManager.maybeRun()
    }
    fun toggleAlbum(bucketId: String) = viewModelScope.launch {
        val cur = settingsStore.backupAlbumIds.first().toMutableSet()
        if (!cur.add(bucketId)) cur.remove(bucketId)
        settingsStore.setBackupAlbumIds(cur)
    }
    fun backupNow() = backupManager.maybeRun()
```
Add imports: `DeviceAlbum`, `Dispatchers`, `withContext`, `MutableStateFlow`, `asStateFlow`, `first` as needed.

- [ ] **Step 2: Add the `BACKUP` route**

In `SettingsScreen.kt`:
- Add `BACKUP` to `enum class SettingsRoute`.
- Add a title branch: `SettingsRoute.BACKUP -> stringResource(R.string.settings_cat_backup)`.
- Add a `SettingsRoot` entry (a `CategoryRow` like the others) navigating to `SettingsRoute.BACKUP`.
- Collect the new state in the parent: `val backupEnabled by vm.backupEnabled...`, `val backupAlbumIds by ...`, `val albums by vm.albums...`.
- Add the `when(route)` branch:
```kotlin
                SettingsRoute.BACKUP -> BackupSettings(
                    padding = innerPadding,
                    enabled = backupEnabled,
                    albums = albums,
                    selected = backupAlbumIds,
                    onSetEnabled = { on ->
                        vm.setBackupEnabled(on)
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mediaLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
                        }
                    },
                    onToggleAlbum = vm::toggleAlbum,
                    onBackupNow = vm::backupNow,
                    onLoadAlbums = vm::loadAlbums,
                )
```
Add a `mediaLauncher` (RequestMultiplePermissions) + `Manifest`/`Build` imports at the top of `SettingsScreen`, mirroring the existing `notificationsLauncher`.

- [ ] **Step 3: Implement the `BackupSettings` composable**

Add to `SettingsScreen.kt`, mirroring `OfflineSettings`:
```kotlin
@Composable
private fun BackupSettings(
    padding: PaddingValues,
    enabled: Boolean,
    albums: List<DeviceAlbum>,
    selected: Set<String>,
    onSetEnabled: (Boolean) -> Unit,
    onToggleAlbum: (String) -> Unit,
    onBackupNow: () -> Unit,
    onLoadAlbums: () -> Unit,
) {
    LaunchedEffect(enabled) { if (enabled) onLoadAlbums() }
    SubScreen(padding) {
        SectionHeader(stringResource(R.string.settings_backup_section))
        SwitchRow(
            title = stringResource(R.string.settings_backup_title),
            subtitle = stringResource(R.string.settings_backup_subtitle),
            checked = enabled,
            onCheckedChange = onSetEnabled,
        )
        if (enabled) {
            SectionHeader(stringResource(R.string.settings_backup_albums))
            albums.forEach { a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = { Text(stringResource(R.string.settings_backup_album_count, a.count)) },
                    trailingContent = {
                        Checkbox(checked = a.bucketId in selected, onCheckedChange = { onToggleAlbum(a.bucketId) })
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onToggleAlbum(a.bucketId) },
                )
            }
            Text(
                stringResource(R.string.settings_backup_status, backedUpCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedButton(onClick = onBackupNow, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_backup_now))
            }
        }
    }
}
```
Add imports: `androidx.compose.material3.Checkbox`, `androidx.compose.material3.ListItem`, `androidx.compose.foundation.clickable`, `de.ledgerline.app.data.backup.DeviceAlbum`, `androidx.compose.runtime.LaunchedEffect` (if missing).

Add `backedUpCount: Int` to the `BackupSettings` params and pass `backedUpCount = backedUpCount` from the route branch. In `SettingsViewModel` add:
```kotlin
    private val _backedUpCount = MutableStateFlow(0)
    val backedUpCount: StateFlow<Int> = _backedUpCount.asStateFlow()
    // refresh the count whenever albums are (re)loaded
```
and set `_backedUpCount.value = backupStateStore.backedUpIds().size` inside `loadAlbums()` (inject `private val backupStateStore: BackupStateStore`). Collect `val backedUpCount by vm.backedUpCount...` in the parent.

**Status scope (deferred):** v1 shows only the live foreground progress (`OpProgressOverlay` + notification) plus this cumulative "M backed up" count. The finer "N pending · K failed this run · last-run time" line from spec §7 is a later refinement — it needs per-run stats plumbed out of `ImportPhotos`/`OperationManager`, which is out of scope for this plan.

- [ ] **Step 4: Add strings**

`values/strings.xml`:
```xml
    <string name="settings_cat_backup">Photo backup</string>
    <string name="settings_backup_section">Camera backup</string>
    <string name="settings_backup_title">Back up camera roll</string>
    <string name="settings_backup_subtitle">Encrypt and upload selected albums to the gallery while unlocked.</string>
    <string name="settings_backup_albums">Albums to back up</string>
    <string name="settings_backup_album_count">%1$d items</string>
    <string name="settings_backup_status">%1$d photos backed up</string>
    <string name="settings_backup_now">Back up now</string>
```
`values-de/strings.xml`:
```xml
    <string name="settings_cat_backup">Foto-Backup</string>
    <string name="settings_backup_section">Kamera-Backup</string>
    <string name="settings_backup_title">Kamerarolle sichern</string>
    <string name="settings_backup_subtitle">Gewählte Alben im entsperrten Zustand verschlüsseln und in die Galerie hochladen.</string>
    <string name="settings_backup_albums">Zu sichernde Alben</string>
    <string name="settings_backup_album_count">%1$d Elemente</string>
    <string name="settings_backup_status">%1$d Fotos gesichert</string>
    <string name="settings_backup_now">Jetzt sichern</string>
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/settings/ app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml
git commit -m "feat(backup): photo-backup settings screen + album picker"
```

---

### Task 9: Trigger backup on unlock

**Files:**
- Modify: `app/src/main/java/de/ledgerline/app/ui/workspace/WorkspaceViewModel.kt`

- [ ] **Step 1: Inject the manager + call it on unlock**

`WorkspaceViewModel.kt`: add `private val backupManager: GalleryBackupManager` to the constructor, and in `ensureLoaded()` after `prefetcher.maybePrefetchOnUnlock()`:
```kotlin
            backupManager.maybeRun()
```
Add `import de.ledgerline.app.core.backup.GalleryBackupManager`.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Full build + unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`; all backup tests green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/workspace/WorkspaceViewModel.kt
git commit -m "feat(backup): trigger backup run on unlock"
```

---

## Manual verification (on device, after Task 9)

1. Fresh install, pair, unlock. Grant photo access on the welcome screen (or in settings).
2. Settings → Photo backup → enable → select the Camera album → observe the foreground-service notification + progress overlay as photos upload.
3. Open the gallery — the device photos appear. Re-run "Back up now" — nothing re-uploads (dedup).
4. Lock the app mid-run (or background + idle) — the run stops cleanly; next unlock resumes remaining items.
5. Toggle Wi-Fi-only on, drop to mobile data — a new run does not start (constraint gate).

---

## Notes for the implementer

- **Do NOT re-implement upload/encryption/process/dedup** — `ImportPhotos` owns all of it. The manager only discovers media, gates, and marks state.
- The `OperationManager.run` block is `suspend (report: (Int,Int)->Unit) -> Unit`; pass progress straight through to `ImportPhotos`.
- The manager must be resilient to a lock mid-run: `ImportPhotos` fetches ciphertext/encrypts per-item and writes the store per-item, so a killed run leaves no half-committed photo; orphaned blobs are swept by the existing `reconcile`.
- Keep the `ContentResolver` seam: unit tests stub `query(...)`/`openInputStream(...)`; never touch a real device provider in tests.
