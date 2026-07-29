package de.ledgerline.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure URL-parsing checks for the share-link recipient flow (no crypto/network). */
class SharedLinkParseTest {

    private val repo = SharedLinkRepository(
        sessionHolder = io.mockk.mockk(relaxed = true),
        shareCrypto = io.mockk.mockk(relaxed = true),
        crypto = io.mockk.mockk(relaxed = true),
        apiProvider = { throw NotImplementedError() },
    )

    @Test fun parses_https_share_link_with_sk_fragment() {
        val p = repo.parse("https://home.example.de/s/abc123#s:U0VDUkVU")
        assertEquals("abc123", p?.token)
        assertEquals("U0VDUkVU", p?.shareKey)
        assertEquals("home.example.de", p?.host)
    }

    @Test fun url_decodes_the_share_key() {
        val p = repo.parse("https://h.test/s/tok#s:a%2Bb%2Fc")
        assertEquals("a+b/c", p?.shareKey)
    }

    @Test fun rejects_link_without_sk_fragment() {
        assertNull(repo.parse("https://h.test/s/tok"))
    }

    @Test fun rejects_non_share_url() {
        assertNull(repo.parse("https://h.test/files/tok#s:x"))
        assertNull(repo.parse("not a url at all"))
    }
}
