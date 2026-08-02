package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.MutateGallery
import de.ledgerline.app.domain.usecase.PhotoSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPhotosImplTest {

    private fun cache(vararg sigs: String): GalleryCache {
        val c = GalleryCache()
        c.set(Gallery(GalleryManifest(photos = sigs.map { s -> mockk<GalleryPhoto>(relaxed = true).also { every { it.sig } returns s } }), 0))
        return c
    }

    private fun source(bytes: ByteArray, name: String = "IMG.jpg") = PhotoSource(
        name = name, mime = "image/jpeg", size = bytes.size.toLong(),
        openInput = { java.io.ByteArrayInputStream(bytes) },
    )

    private fun okPhoto() = mockk<GalleryPhoto>(relaxed = true)

    @Test fun `dedups identical bytes within a batch - uploads once`() = runTest {
        val uploader = mockk<GalleryUploader>()
        coEvery { uploader.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns Outcome.Ok(okPhoto())
        val mutate = mockk<MutateGallery>()
        coEvery { mutate.invoke(any()) } returns Outcome.Ok(mockk(relaxed = true))
        val bytes = ByteArray(2048) { it.toByte() }

        val r = ImportPhotosImpl(cache(), uploader, mutate).invoke(listOf(source(bytes), source(bytes))) { _, _ -> }

        assertEquals(2, r.done)
        assertEquals(0, r.failed)
        coVerify(exactly = 1) { uploader.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { mutate.invoke(any()) } // one commit for the single new photo
    }

    @Test fun `skips an item already in the index by sig`() = runTest {
        val bytes = ByteArray(4096) { (it * 7).toByte() }
        val sig = fileSig(bytes)
        val uploader = mockk<GalleryUploader>(relaxed = true)
        val mutate = mockk<MutateGallery>(relaxed = true)

        val r = ImportPhotosImpl(cache(sig), uploader, mutate).invoke(listOf(source(bytes))) { _, _ -> }

        assertEquals(0, r.failed)
        coVerify(exactly = 0) { uploader.upload(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `commits in batches of eight`() = runTest {
        val uploader = mockk<GalleryUploader>()
        coEvery { uploader.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns Outcome.Ok(okPhoto())
        val mutate = mockk<MutateGallery>()
        coEvery { mutate.invoke(any()) } returns Outcome.Ok(mockk(relaxed = true))
        val sources = (0 until 10).map { source(ByteArray(1024) { b -> (b + it).toByte() }, "IMG_$it.jpg") }

        ImportPhotosImpl(cache(), uploader, mutate).invoke(sources) { _, _ -> }

        coVerify(exactly = 2) { mutate.invoke(any()) } // 8 + 2
    }

    @Test fun `quota rejection sets flag`() = runTest {
        val uploader = mockk<GalleryUploader>()
        coEvery { uploader.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns Outcome.Err(ErrorKind.QUOTA)
        val mutate = mockk<MutateGallery>(relaxed = true)

        val r = ImportPhotosImpl(cache(), uploader, mutate).invoke(listOf(source(ByteArray(512) { 1 }))) { _, _ -> }

        assertTrue(r.quotaExceeded)
        assertEquals(1, r.failed)
    }

    @Test fun `commit failure demotes the batch to failed`() = runTest {
        val uploader = mockk<GalleryUploader>()
        coEvery { uploader.upload(any(), any(), any(), any(), any(), any(), any(), any()) } returns Outcome.Ok(okPhoto())
        val mutate = mockk<MutateGallery>()
        coEvery { mutate.invoke(any()) } returns Outcome.Err(ErrorKind.NETWORK)
        val src = source(ByteArray(700) { 3 })

        val r = ImportPhotosImpl(cache(), uploader, mutate).invoke(listOf(src)) { _, _ -> }

        assertEquals(1, r.failed)
        assertEquals(listOf(src), r.failedSources)
        assertFalse(r.quotaExceeded)
    }

    /** Mirror of the impl's windowed sig so a test can seed the cache with a matching one. */
    private fun fileSig(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val cap = 1024 * 1024
        md.update(bytes, 0, minOf(cap, bytes.size))
        if (bytes.size > cap) md.update(bytes, bytes.size - cap, cap)
        return "${bytes.size}:${md.digest().joinToString("") { "%02x".format(it) }}"
    }
}
