package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForceLogoutImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun invoke_wipes_all_in_memory_state_and_clears_persisted_session_and_keystore() = runTest {
        val vaultKeyHolder = VaultKeyHolder().apply { set(ByteArray(32) { 7 }) }
        val sessionHolder = SessionHolder().apply {
            set(Session("https://h.example", "tok", "sha256/AAA", "Malte"))
        }
        val workspaceCache = WorkspaceCache().apply {
            set(Workspace(WorkspaceManifest(), version = 1))
        }
        val galleryCache = GalleryCache()
        val thumbCache = ThumbCache()
        val metaCache = MetaCache().apply { put("p1", null) }

        // The two Android-touching deps (DataStore / AndroidKeystore) are mocked; we
        // assert their clear() is invoked as part of the wipe.
        val sessionStore = mockk<SessionStore>(relaxed = true)
        val keystoreSealer = mockk<KeystoreSealer>(relaxed = true)
        coEvery { sessionStore.clear() } returns Unit
        every { keystoreSealer.clear() } returns Unit

        // Real disk caches over temp dirs, pre-populated; assert they end up empty.
        val storeCache = StoreDiskCache(tmp.newFolder("storecache")).apply {
            put("workspace", StoreEnvelope("cipher", 1))
        }
        val blobCache = BlobDiskCache(tmp.newFolder("blobcache")).apply {
            put("blob-1", ByteArray(16) { 3 })
        }

        val forceLogout = ForceLogoutImpl(
            sessionStore = sessionStore,
            keystoreSealer = keystoreSealer,
            vaultKeyHolder = vaultKeyHolder,
            sessionHolder = sessionHolder,
            workspaceCache = workspaceCache,
            galleryCache = galleryCache,
            thumbCache = thumbCache,
            metaCache = metaCache,
            storeCache = storeCache,
            blobCache = blobCache,
        )

        forceLogout.invoke()

        // In-memory secrets + decrypted caches cleared.
        assertNull(vaultKeyHolder.get())
        assertFalse(vaultKeyHolder.unlocked.value)
        assertNull(sessionHolder.get())
        assertNull(workspaceCache.value.value)
        assertNull(galleryCache.value.value)
        assertFalse(metaCache.has("p1"))

        // Persisted session + auth-gated keystore key deleted (re-pair required).
        coVerify(exactly = 1) { sessionStore.clear() }
        verify(exactly = 1) { keystoreSealer.clear() }

        // Offline ciphertext caches wiped.
        assertEquals(0L, storeCache.sizeBytes())
        assertEquals(0L, blobCache.sizeBytes())
        assertNull(storeCache.get("workspace"))
        assertNull(blobCache.get("blob-1"))
    }
}
