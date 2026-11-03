package com.intunix.sdk.ui

import com.intunix.sdk.IntunixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test fun developerBeatsLockBeatsHost() {
        assertFalse(resolveDark(IntunixTheme.Light, "dark", true))
        assertTrue(resolveDark(IntunixTheme.Dark, "light", false))
        assertFalse(resolveDark(IntunixTheme.Auto, "light", true))
        assertTrue(resolveDark(IntunixTheme.Auto, "dark", false))
        assertTrue(resolveDark(IntunixTheme.Auto, null, true))
        assertFalse(resolveDark(IntunixTheme.Auto, "auto", false))
    }

    @Test fun defaultDarkIsReadable() {
        val p = darkPalette(null)
        assertEquals(parseHexColor(DEFAULT_DARK_BACKGROUND), p.card)
        assertTrue(contrast(p.text, p.card) >= 7f)
        assertTrue(contrast(p.muted, p.card) >= 4.5f)
    }

    @Test fun invalidBackgroundFallsBack() {
        assertEquals(parseHexColor(DEFAULT_DARK_BACKGROUND), darkPalette("red; x").card)
        assertEquals(parseHexColor("#0b1220"), darkPalette("#0b1220").card)
    }

    @Test fun lightBackgroundStillReadable() {
        val p = darkPalette("#f5f5f5")
        assertTrue(contrast(p.text, p.card) >= 7f)
        assertTrue(contrast(p.muted, p.card) >= 4.5f)
    }

    @Test fun invisibleBrandIsLightened() {
        val p = darkPalette(null)
        val b = brandColors("#18181b", p)
        assertNotEquals(parseHexColor("#18181b"), b.color)
        assertTrue(contrast(b.color, p.card) >= 3f)
        assertEquals(parseHexColor("#22c55e"), brandColors("#22c55e", p).color)
    }

    @Test fun lightPaletteMatchesCss() {
        assertEquals(parseHexColor("#2F9461"), brandColors("#2F9461").color)
        assertEquals(parseHexColor("#18181b"), LightPalette.text)
    }
}
