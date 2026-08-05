package de.ledgerline.app.core.security

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VaultLockerTest {

    @Test
    fun lock_wipes_vault_key_and_clears_all_in_memory_caches() {
        val vaultKeyHolder = VaultKeyHolder().apply { set(byteArrayOf(1, 2, 3)) }
        val sessionHolder = SessionHolder().apply {
            set(Session("https://h.example", "tok", "sha256/AAA", "Malte"))
        }
        val workspaceCache = WorkspaceCache().apply {
            set(Workspace(WorkspaceManifest(), version = 1))
        }
        val galleryCache = GalleryCache()
        val metaCache = MetaCache().apply { put("p1", null) }
        // ThumbCache references android.graphics.Bitmap, so mock it and assert
        // clear() is invoked rather than exercising its map on the JVM.
        val thumbCache = mockk<ThumbCache>(relaxed = true)

        val locker = VaultLocker(
            vaultKeyHolder = vaultKeyHolder,
            sessionHolder = sessionHolder,
            workspaceCache = workspaceCache,
            galleryCache = galleryCache,
            thumbCache = thumbCache,
            metaCache = metaCache,
            identityRepository = io.mockk.mockk(relaxed = true),
            sharedVaultRepository = io.mockk.mockk(relaxed = true),
            passwordsCache = de.ledgerline.app.core.PasswordsCache(),
            exploreCache = de.ledgerline.app.core.ExploreCache(),
            healthCache = de.ledgerline.app.core.HealthCache(),
            financeCache = de.ledgerline.app.core.FinanceCache(),
            calendarCache = de.ledgerline.app.core.CalendarCache(),
        )

        locker.lock()

        assertNull(vaultKeyHolder.get())
        assertFalse(vaultKeyHolder.unlocked.value)
        assertNull(sessionHolder.get())
        assertNull(workspaceCache.value.value)
        assertNull(galleryCache.value.value)
        assertFalse(metaCache.has("p1"))
        verify(exactly = 1) { thumbCache.clear() }
    }
}
