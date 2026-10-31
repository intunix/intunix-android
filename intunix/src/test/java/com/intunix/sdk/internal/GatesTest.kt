package com.intunix.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatesTest {
    private fun exp(json: String): Experience =
        parseExperience(JSONObject("""{"id":"f1","type":"modal","content":{"kind":"feedback","steps":[]},"triggers":[]}""").also { base ->
            val extra = JSONObject(json)
            extra.keys().forEach { base.put(it, extra.get(it)) }
        })!!

    private val user = GateUser("u1", mapOf("plan" to "pro", "seats" to 12))

    @Test fun platformAbsentMeansEverywhere() = assertTrue(checkPlatform(exp("{}")))
    @Test fun webOnlyFormSkipsApps() = assertFalse(checkPlatform(exp("""{"platforms":["web"]}""")))
    @Test fun appFormShows() = assertTrue(checkPlatform(exp("""{"platforms":["web","app"]}""")))

    @Test fun legacyTraitEqualsFailsClosedForAnonymous() {
        val e = exp("""{"targeting":{"traitEquals":{"plan":"pro"}}}""")
        assertFalse(checkTargeting(e, null, emptyMap(), ""))
        assertTrue(checkTargeting(e, user, emptyMap(), ""))
        assertFalse(checkTargeting(e, user.copy(traits = mapOf("plan" to "free")), emptyMap(), ""))
    }

    @Test fun ruleOps() {
        fun rule(r: String) = exp("""{"targeting":{"rules":[$r]}}""")
        assertTrue(checkTargeting(rule("""{"kind":"trait","key":"seats","op":"gt","value":10}"""), user, emptyMap(), ""))
        assertFalse(checkTargeting(rule("""{"kind":"trait","key":"seats","op":"lt","value":10}"""), user, emptyMap(), ""))
        assertTrue(checkTargeting(rule("""{"kind":"trait","key":"plan","op":"in","value":["pro","team"]}"""), user, emptyMap(), ""))
        assertFalse(checkTargeting(rule("""{"kind":"trait","key":"plan","op":"nin","value":"pro"}"""), user, emptyMap(), ""))
        assertTrue(checkTargeting(rule("""{"kind":"trait","key":"missing","op":"neq","value":"x"}"""), user, emptyMap(), ""))
        assertFalse(checkTargeting(rule("""{"kind":"trait","key":"missing","op":"eq","value":"x"}"""), user, emptyMap(), ""))
        assertTrue(checkTargeting(rule("""{"kind":"context","key":"page","op":"exists"}"""), user, mapOf("page" to "home"), ""))
        assertFalse(checkTargeting(rule("""{"kind":"context","key":"page","op":"exists"}"""), user, emptyMap(), ""))
        assertTrue(checkTargeting(rule("""{"kind":"url","op":"contains","value":"Checkout"}"""), user, emptyMap(), "CheckoutScreen"))
        assertTrue(checkTargeting(rule("""{"kind":"context","key":"n","op":"eq","value":3}"""), user, mapOf("n" to 3.0), ""))
    }

    @Test fun eligibilityWindows() {
        val p = ProfileData(firstSeenAt = 0, sessionCount = 2, lastSeenAt = 0, outcomes = mutableMapOf())
        assertFalse(checkEligibility(exp("""{"eligibility":{"minSessions":3}}"""), p, 10))
        assertTrue(checkEligibility(exp("""{"eligibility":{"minDaysSinceFirstSeen":1}}"""), p, DAY_MS + 1))
        val at = parseIsoMillis("2026-01-01T00:00:00Z")!!
        assertFalse(checkEligibility(exp("""{"eligibility":{"startAt":"2026-01-01T00:00:00Z"}}"""), p, at - 1))
        assertTrue(checkEligibility(exp("""{"eligibility":{"startAt":"2026-01-01T00:00:00Z"}}"""), p, at))
        assertFalse(checkEligibility(exp("""{"eligibility":{"endAt":"not a date"}}"""), p, at))
    }

    @Test fun isoParsing() {
        assertEquals(1767225600000L, parseIsoMillis("2026-01-01T00:00:00.000Z"))
        assertEquals(1767225600000L, parseIsoMillis("2026-01-01"))
        assertNull(parseIsoMillis("2026-13-45"))
    }

    @Test fun identityKeyScopesTraitsAndContext() {
        val e = exp("""{"targeting":{"rules":[{"kind":"trait","key":"plan","op":"exists"},{"kind":"context","key":"page","op":"exists"}]},"frequencyScope":"form_context"}""")
        assertEquals("u1|plan=pro|ctx={\"page\":\"home\"}", identityKey(e, "u1", user.traits, mapOf("page" to "home", "other" to 1)))
        assertEquals("anon", identityKey(exp("{}"), "anon", user.traits, emptyMap()))
    }

    @Test fun storesMergeIdentity() {
        val store = MemoryStore()
        val profile = ProfileStore(store) { 1000 }
        profile.recordOutcome("f1", "anon-1|role=tutor", "responded")
        profile.mergeIdentity("anon-1", "user-9")
        assertTrue(profile.outcomes("f1", "user-9|role=tutor")?.respondedAt != null)
        assertNull(profile.outcomes("f1", "anon-1|role=tutor"))

        val shown = ShownStore(store)
        shown.markShown("f1", "anon-1", 5, "s")
        shown.markShown("f1", "anon-1", 6, "s")
        shown.mergeIdentity("anon-1", "user-9")
        assertEquals(2, shown.get("f1", "user-9")?.count)
    }

    @Test fun sessionMintsOnlyOnExpiry() {
        var now = 0L
        val s = SessionManager(MemoryStore()) { now }
        val seen = mutableListOf<String>()
        s.onNewSession { seen.add(it) }
        val a = s.id()
        s.id()
        now += SessionManager.TTL_MS + 1
        val b = s.id()
        assertEquals(listOf(a, b), seen)
    }
}
