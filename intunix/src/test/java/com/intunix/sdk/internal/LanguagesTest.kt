package com.intunix.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    @Test fun mapsChineseRegionsToTheScriptTheFormLists() {
        val offered = listOf("en", "zh-Hans", "zh-Hant")
        assertEquals("zh-Hant", matchLanguage("zh-TW", offered))
        assertEquals("zh-Hant", matchLanguage("zh-HK", offered))
        assertEquals("zh-Hans", matchLanguage("zh-CN", offered))
        assertEquals("zh-Hans", matchLanguage("zh", offered))
        assertEquals("zh-Hans", matchLanguage("zh-TW", listOf("en", "zh-Hans")))
    }

    @Test fun fallsBackToTheBaseLanguage() {
        assertEquals("zh", matchLanguage("zh-Hant-TW", listOf("en", "zh")))
        assertNull(matchLanguage("zh-Hant-TW", listOf("en", "bn")))
    }

    @Test fun prefersTheExactTagAndIgnoresCase() {
        assertEquals("en-GB", matchLanguage("EN-gb", listOf("en", "en-GB")))
        assertEquals("bn", matchLanguage("BN", listOf("bn")))
    }

    @Test fun truncatesSubtagsThenMatchesTheBaseInOfferedOrder() {
        assertEquals("zh-Hant", matchLanguage("zh-Hant-TW", listOf("en", "zh-Hans", "zh-Hant")))
        assertEquals("zh-Hans", matchLanguage("zh", listOf("en", "zh-Hans", "zh-Hant")))
        assertEquals("pt-BR", matchLanguage("pt-AO", listOf("pt-BR", "pt-PT")))
        assertEquals("pt-PT", matchLanguage("pt_pt", listOf("pt-BR", "pt-PT")))
        assertEquals("en-gb", matchLanguage(" en_GB ", listOf("en-gb")))
    }

    @Test fun neverThrowsOnBadInput() {
        assertNull(matchLanguage(null, listOf("en")))
        assertNull(matchLanguage("", listOf("en")))
        assertNull(norm(null).takeIf { it.isNotEmpty() })
        assertEquals("", norm(7))
    }

    @Test fun knowsDirectionAndOwnNames() {
        assertTrue(languageInfo("ar").rtl)
        assertTrue(languageInfo("ur-PK").rtl)
        assertFalse(languageInfo("en").rtl)
        assertEquals("বাংলা", languageInfo("bn").name)
        assertEquals("English", languageInfo("en").name)
    }

    @Test fun rtlBases() {
        for (c in listOf("ar", "fa", "he", "ur", "ar-EG")) assertTrue(c, languageInfo(c).rtl)
        for (c in listOf("en", "bn")) assertFalse(c, languageInfo(c).rtl)
    }

    @Test fun echoesAnUnknownCode() = assertEquals("xx", languageInfo("xx").name)

    @Test fun tellsRegionalVariantsApart() {
        assertEquals("Português (Brasil)", languageInfo("pt-BR").name)
        assertEquals("Português (Portugal)", languageInfo("pt_PT").name)
        assertEquals("简体中文", languageInfo("zh-Hans").name)
        assertEquals("English (UK)", languageInfo("en-GB").name)
        assertEquals("Español (Latinoamérica)", languageInfo("es-419").name)
    }

    @Test fun tableNamesWinOverThePlatform() {
        assertEquals("Bahasa Indonesia", languageInfo("id").name)
        assertEquals("Português (Brasil)", languageInfo("pt-BR").name)
    }

    @Test fun namesAreTableDriven() {
        val base = mapOf(
            "en" to "English", "bn" to "বাংলা", "hi" to "हिन्दी", "ur" to "اردو", "ar" to "العربية", "es" to "Español", "fr" to "Français",
            "de" to "Deutsch", "pt" to "Português", "id" to "Bahasa Indonesia", "tr" to "Türkçe", "zh" to "中文", "ja" to "日本語",
        )
        val regional = mapOf(
            "pt-BR" to "Português (Brasil)", "pt-PT" to "Português (Portugal)", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文",
            "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "en-US" to "English (US)", "en-GB" to "English (UK)",
            "es-419" to "Español (Latinoamérica)", "es-ES" to "Español (España)", "fr-CA" to "Français (Canada)",
        )
        for ((c, n) in base + regional) assertEquals(c, n, languageInfo(c).name)
    }

    // ---- resolveLanguage ----
    private fun form(languages: List<String>, def: String? = null) = JSONObject()
        .put("kind", "feedback").put("languages", org.json.JSONArray(languages)).also { if (def != null) it.put("defaultLanguage", def) }

    @Test fun resolvePrefersRememberedThenDeveloperThenDeviceThenDefault() {
        val f = form(listOf("en", "bn", "ar"))
        assertEquals("ar", resolveLanguage(f, "ar", "bn", listOf("bn")))
        assertEquals("bn", resolveLanguage(f, null, "bn", listOf("ar")))
        assertEquals("ar", resolveLanguage(f, null, null, listOf("fr", "ar-EG")))
        assertEquals("en", resolveLanguage(f, null, null, listOf("fr")))
        assertEquals("bn", resolveLanguage(form(listOf("bn", "en"), "bn"), null, null, emptyList()))
    }

    @Test fun resolveFallsThroughALanguageTheFormDoesNotOffer() {
        val f = form(listOf("en", "bn", "ar"))
        assertEquals("bn", resolveLanguage(f, "fr", null, listOf("bn")))
        assertEquals("en", resolveLanguage(f, "fr", "de", emptyList()))
    }

    @Test fun resolveReturnsTheOfferedCodeAndMapsZhHantTwToZh() {
        assertEquals("zh", resolveLanguage(form(listOf("en", "zh")), null, null, listOf("zh-Hant-TW")))
        assertEquals("en", resolveLanguage(form(listOf("en", "bn")), null, null, listOf("zh-Hant-TW")))
    }
}
