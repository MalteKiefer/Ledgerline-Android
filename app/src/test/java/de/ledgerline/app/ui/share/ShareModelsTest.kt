package de.ledgerline.app.ui.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareModelsTest {
    @Test fun image_is_gallery() = assertEquals(ShareTarget.GALLERY, classify("image/png"))
    @Test fun video_is_gallery() = assertEquals(ShareTarget.GALLERY, classify("video/mp4"))
    @Test fun pdf_is_files() = assertEquals(ShareTarget.FILES, classify("application/pdf"))
    @Test fun text_is_files() = assertEquals(ShareTarget.FILES, classify("text/plain"))
    @Test fun null_is_files() = assertEquals(ShareTarget.FILES, classify(null))
    @Test fun wildcard_is_files() = assertEquals(ShareTarget.FILES, classify("*/*"))
}
