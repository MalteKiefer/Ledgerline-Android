package de.ledgerline.app.ui.workspace.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilesTest {

    @Test fun textPlain_mime_is_text() {
        assertTrue(isTextFile("text/plain", "notes.txt"))
    }

    @Test fun json_mime_is_text() {
        assertTrue(isTextFile("application/json", "data"))
    }

    @Test fun structured_suffix_mimes_are_text() {
        assertTrue(isTextFile("application/vnd.api+json", "x"))
        assertTrue(isTextFile("image/svg+xml", "x"))
    }

    @Test fun mime_with_charset_param_still_text() {
        assertTrue(isTextFile("text/markdown; charset=utf-8", "readme"))
        assertTrue(isTextFile("application/json; charset=utf-8", "x"))
    }

    @Test fun code_extensions_are_text() {
        assertTrue(isTextFile(null, "Main.kt"))
        assertTrue(isTextFile("", "script.py"))
        assertTrue(isTextFile(null, "config.json"))
        assertTrue(isTextFile(null, "README.md"))
        assertTrue(isTextFile(null, "pom.xml"))
        assertTrue(isTextFile(null, "app.yaml"))
    }

    @Test fun extensionless_common_names_are_text() {
        assertTrue(isTextFile(null, "Dockerfile"))
        assertTrue(isTextFile(null, "Makefile"))
        assertTrue(isTextFile("application/octet-stream", ".gitignore"))
        assertTrue(isTextFile(null, ".env"))
    }

    @Test fun images_are_not_text() {
        assertFalse(isTextFile("image/png", "photo.png"))
        assertFalse(isTextFile(null, "photo.jpg"))
    }

    @Test fun pdf_is_not_text() {
        assertFalse(isTextFile("application/pdf", "doc.pdf"))
    }

    @Test fun binary_and_unknown_are_not_text() {
        assertFalse(isTextFile(null, "clip.mp4"))
        assertFalse(isTextFile("application/octet-stream", "blob.bin"))
        assertFalse(isTextFile(null, "archive.zip"))
    }

    @Test fun empty_name_and_mime_is_not_text() {
        assertFalse(isTextFile(null, ""))
        assertFalse(isTextFile("", ""))
    }

    @org.junit.Test fun asc_pub_pem_and_extensionless_are_text() {
        org.junit.Assert.assertTrue(isTextFile(null, "key.asc"))
        org.junit.Assert.assertTrue(isTextFile(null, "id_rsa.pub"))
        org.junit.Assert.assertTrue(isTextFile(null, "cert.pem"))
        org.junit.Assert.assertTrue(isTextFile(null, "LICENSE"))
        org.junit.Assert.assertTrue(isTextFile(null, "CHANGELOG"))
    }

    @org.junit.Test fun looksLikeText_accepts_printable_rejects_nul() {
        org.junit.Assert.assertTrue(looksLikeText("-----BEGIN PGP PUBLIC KEY-----\nabc\n".toByteArray()))
        org.junit.Assert.assertTrue(looksLikeText(ByteArray(0)))
        org.junit.Assert.assertFalse(looksLikeText(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x0D)))
    }

    @org.junit.Test fun isEditableText_sniffs_extensionless_text() {
        // No extension, unknown mime, but printable content → editable.
        org.junit.Assert.assertTrue(isEditableText("application/octet-stream", "randomfile", "hello world\n".toByteArray()))
        // Binary (NUL) → not editable.
        org.junit.Assert.assertFalse(isEditableText("application/octet-stream", "blob", byteArrayOf(1,0,2,0)))
    }

}
