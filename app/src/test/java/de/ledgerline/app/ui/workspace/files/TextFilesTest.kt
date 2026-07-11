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
}
