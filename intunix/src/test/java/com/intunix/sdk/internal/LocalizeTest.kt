package com.intunix.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalizeTest {
    private fun res(p: String) = javaClass.getResource(p)!!.readText()

    @Test fun sharedVectors() {
        val vectors = JSONArray(res("/i18n/localize.json"))
        assertEquals(23, vectors.length())
        for (v in vectors.objects()) {
            val name = v.getString("name")
            val content = v.getJSONObject("content")
            val before = content.toString()
            val out = localizeContent(content, v.getString("lang"))
            val expected = v.getJSONObject("expected")
            assertEquals(name, fromJson(expected), fromJson(out))
            assertEquals("$name: input mutated", before, content.toString())
            if (expected.has("translations") || expected.has("languages")) assertSame(name, content, out)
        }
    }

    @Test fun builtinTableMatchesWeb() {
        val web = JSONObject(res("/i18n/builtin.json"))
        // org.json objects are unordered, so the order is pinned by the literal list.
        assertEquals(web.keys().asSequence().toSet(), BUILTIN_LANGUAGES.toSet())
        assertEquals(listOf("en", "bn", "hi", "ur", "ar", "es", "fr", "de", "pt", "id", "tr", "zh", "ja"), BUILTIN_LANGUAGES)
        for (l in BUILTIN_LANGUAGES) assertEquals(l, fromJson(web.getJSONObject(l)), builtinTexts(l).toMapForTest())
    }

    @Test fun fillStepFillsOnlyNonEnglish() {
        val s = JSONObject("""{"id":"probe:s1:1","kind":"followup","title":"Why?","input":{"type":"free"}}""")
        assertSame(s, fillStep(s, "en-GB"))
        assertEquals("আপনার উত্তর লিখুন…", fillStep(s, "bn").getJSONObject("input").getString("placeholder"))
    }

    @Test fun selectInputAndPmfShareOptionTranslation() {
        val s = JSONObject("""{"id":"q","kind":"followup","title":"T","input":{"type":"selectOne","options":[{"value":"a","label":"A"}]}}""")
        val c = JSONObject("""{"kind":"feedback","languages":["en","bn"],"steps":[$s],"translations":{"bn":{"steps":{"q":{"options":{"a":"ক"}}}}}}""")
        val o = localizeContent(c, "bn").getJSONArray("steps").getJSONObject(0).getJSONObject("input").getJSONArray("options")
        assertEquals("ক", o.getJSONObject(0).getString("label"))
    }
}

private fun BuiltinTexts.toMapForTest(): Map<String, Any?> = mapOf(
    "submit" to submit, "next" to next, "back" to back, "done" to done, "pageOf" to pageOf, "questionOf" to questionOf, "thanksTitle" to thanksTitle, "thanksSubtitle" to thanksSubtitle, "npsLow" to npsLow, "npsHigh" to npsHigh, "enpsLow" to enpsLow, "enpsHigh" to enpsHigh, "cesLow" to cesLow, "cesHigh" to cesHigh, "send" to send, "skip" to skip, "end" to end, "typing" to typing, "log" to log, "answer" to answer, "language" to language, "close" to close,
    "csat" to csat, "pmf" to pmf,
)
