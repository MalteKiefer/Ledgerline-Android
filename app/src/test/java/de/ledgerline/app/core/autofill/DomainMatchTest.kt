package de.ledgerline.app.core.autofill

import de.ledgerline.app.domain.model.SecretItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatchTest {

    private fun login(vararg urls: String, username: String = "me@example.com") = SecretItem(
        id = "1", type = "login", title = "Example",
        fields = buildJsonObject {
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive("s3cret"))
            put("urls", buildJsonArray { urls.forEach { add(JsonPrimitive(it)) } })
        },
    )

    @Test fun normalizeStripsSchemePathAndWww() {
        assertEquals("example.com", DomainMatch.normalizeHost("https://www.example.com/login?x=1"))
        assertEquals("sub.example.com", DomainMatch.normalizeHost("SUB.Example.com:443"))
        assertNull(DomainMatch.normalizeHost("  "))
    }

    @Test fun registrableDomainHandlesTwoPartTlds() {
        assertEquals("example.com", DomainMatch.registrableDomain("login.example.com"))
        assertEquals("example.co.uk", DomainMatch.registrableDomain("www.example.co.uk"))
        assertEquals("example.com", DomainMatch.registrableDomain("example.com"))
    }

    @Test fun matchesByRegistrableDomain() {
        val item = login("https://accounts.example.com/")
        assertTrue(DomainMatch.matches(item, webDomain = "login.example.com", packageName = null))
        assertFalse(DomainMatch.matches(item, webDomain = "evil.com", packageName = null))
    }

    @Test fun matchesNativeAppByPackageToken() {
        val item = login("https://reddit.com/", username = "u")
        assertTrue(DomainMatch.matches(item, webDomain = null, packageName = "com.reddit.frontpage"))
        assertFalse(DomainMatch.matches(item, webDomain = null, packageName = "com.spotify.music"))
    }

    @Test fun nonFillableTypeNeverMatches() {
        val note = SecretItem(id = "2", type = "secure_note", title = "example.com")
        assertFalse(DomainMatch.matches(note, webDomain = "example.com", packageName = null))
    }
}
