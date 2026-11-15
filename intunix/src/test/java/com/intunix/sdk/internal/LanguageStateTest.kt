package com.intunix.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageStateTest {
    private val store = MemoryStore()
    private val client = IntunixClient(
        ClientConfig("k", "http://localhost", false, emptyList(), 60_000, 100, 0, true, "test", locale = "en"),
        store, { _, _, _ -> error("unused") }, CoroutineScope(Job()), { _, _ -> },
    )

    @Test fun languageStoreRoundTripsUnderItsKey() {
        val s = LanguageStore(store)
        assertNull(s.remembered())
        s.remember("bn")
        assertEquals("bn", s.remembered())
        assertEquals("bn", store.get("intunix:lang"))
        s.clear()
        assertNull(s.remembered())
    }

    @Test fun setLocaleChangesDeveloperAndRemembers() {
        assertEquals("en", client.localeSource().developer())
        client.setLocale("bn")
        val src = client.localeSource()
        assertEquals("bn", src.developer())
        assertEquals("bn", src.remembered())
        assertEquals("ar", client.localeSource("ar").developer()) // a per-form locale wins
    }

    @Test fun resetClearsRememberedAndRestoresInitLocale() {
        client.setLocale("bn")
        client.localeSource().remember("ar")
        client.reset()
        assertNull(store.get("intunix:lang"))
        assertNull(client.localeSource().remembered())
        assertEquals("en", client.localeSource().developer())
    }
}
