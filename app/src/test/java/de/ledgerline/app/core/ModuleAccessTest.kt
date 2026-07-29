package de.ledgerline.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleAccessTest {

    @Test fun unknown_list_allows_everything() {
        val a = ModuleAccess()
        assertTrue(a.allows("finance"))
        assertTrue(a.allows("passwords"))
    }

    @Test fun restricts_to_the_listed_modules() {
        val a = ModuleAccess()
        a.set(listOf("files", "gallery", "finance"))
        assertTrue(a.allows("finance"))
        assertTrue(a.allows("files"))
        assertFalse(a.allows("passwords"))
        assertFalse(a.allows("health"))
    }

    @Test fun clear_and_null_return_to_unrestricted() {
        val a = ModuleAccess()
        a.set(listOf("files"))
        assertFalse(a.allows("finance"))
        a.set(null)
        assertTrue(a.allows("finance"))
        a.set(listOf("files"))
        a.clear()
        assertTrue(a.allows("finance"))
    }
}
