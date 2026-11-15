package com.intunix.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FormLanguageTest {
    private class Counting(var remembered: String? = null, var developer: String? = null) {
        var reads = 0
        val remembers = mutableListOf<String>()
        val source = LocaleSource({ reads++; developer }, { reads++; remembered }, { remembers.add(it) })
    }

    private fun raw(extra: String) = JSONObject(
        """{"kind":"feedback","brand":{"color":"#2F9461"},"steps":[{"id":"s1","kind":"nps","title":"One"}]$extra}""",
    )
    private val multi = raw(""","defaultLanguage":"en","languages":["en","bn","ar"],"translations":{"bn":{"steps":{"s1":{"title":"bOne"}}},"ar":{"steps":{"s1":{"title":"aOne"}}}}""")

    @Test fun plainFormReadsNothing() {
        val c = Counting(remembered = "bn", developer = "bn")
        val r = raw(""","defaultLanguage":"en"""")
        val f = FormLanguage(r, c.source, listOf("bn"))
        assertFalse(f.multi); assertFalse(f.decorated); assertNull(f.texts())
        assertEquals(0, c.reads)
        assertEquals(parseFeedbackContent(r), f.content)
    }

    @Test fun rememberedBeatsDeveloperBeatsDevice() {
        val f = FormLanguage(multi, Counting("ar", "bn").source, listOf("bn-BD"))
        assertEquals("ar", f.lang); assertTrue(f.rtl)
        assertEquals("bn", FormLanguage(multi, Counting(null, "bn").source, listOf("ar")).lang)
        assertEquals("bn", FormLanguage(multi, Counting().source, listOf("bn-BD")).lang)
    }

    @Test fun singleLanguageBengaliIsDecorated() {
        val c = Counting()
        val f = FormLanguage(raw(""","defaultLanguage":"bn""""), c.source, emptyList())
        assertTrue(f.decorated); assertFalse(f.multi)
        assertEquals("পরবর্তী", f.texts()!!.next)
        assertEquals(0, c.reads)
    }

    @Test fun switchRemembersAndRelocalizes() {
        val c = Counting()
        val f = FormLanguage(multi, c.source, emptyList())
        assertEquals("One", f.content.steps[0].title)
        assertTrue(f.switchTo("bn"))
        assertEquals(listOf("bn"), c.remembers)
        assertEquals("bOne", f.content.steps[0].title)
        assertFalse(f.switchTo("bn"))
        assertFalse(f.switchTo("fr"))
        assertEquals(1, c.remembers.size)
    }

    @Test fun decorateAddsLanguageOnlyWhenMulti() {
        val r = FeedbackResponse(emptyList(), true, 5)
        assertEquals("en", FormLanguage(multi, Counting().source, emptyList()).decorate(r).language)
        assertSame(r, FormLanguage(raw(""), null, emptyList()).decorate(r))
    }
}
