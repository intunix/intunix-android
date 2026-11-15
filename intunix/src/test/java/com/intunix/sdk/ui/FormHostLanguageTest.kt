package com.intunix.sdk.ui

import com.intunix.sdk.internal.Answer
import com.intunix.sdk.internal.AnswerValue
import com.intunix.sdk.internal.ConversationConfig
import com.intunix.sdk.internal.ConversationHandle
import com.intunix.sdk.internal.ConversationTransport
import com.intunix.sdk.internal.Defaults
import com.intunix.sdk.internal.ExperienceResult
import com.intunix.sdk.internal.LocaleSource
import com.intunix.sdk.internal.Phase
import com.intunix.sdk.internal.RenderCallbacks
import com.intunix.sdk.internal.TurnRequest
import com.intunix.sdk.internal.parseExperience
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FormHostLanguageTest {
    private val langs = """"defaultLanguage":"en","languages":["en","bn","ar"],"translations":{
        "bn":{"steps":{"nps":{"title":"bn nps"},"csat":{"title":"bn csat"}},"thanks":{"title":"bn thanks"}},
        "ar":{"steps":{"nps":{"title":"ar nps"},"csat":{"title":"ar csat"}},"thanks":{"title":"ar thanks"}}}"""

    private fun json(multi: Boolean, ai: Boolean = false, extra: String = "", top: String = "") = JSONObject(
        """{"id":"f","type":"popup","triggers":[]$top,"content":{"kind":"feedback","brand":{"color":"#2F9461"},"thanks":{"title":"Thanks"},
        ${if (multi) langs + "," else ""}"steps":[
        {"id":"nps","kind":"nps","title":"en nps","required":true,"page":1${if (ai) ",\"aiFollowUp\":true" else ""}},
        {"id":"csat","kind":"csat","title":"en csat","required":true,"page":2}]$extra}}""",
    )

    private class Source(val developer: String? = null) {
        var reads = 0
        val remembered = mutableListOf<String>()
        val source = LocaleSource({ reads++; developer }, { reads++; null }, { remembered += it })
    }

    private class Wire(private val reply: (TurnRequest) -> Any) : ConversationTransport {
        val sent = mutableListOf<TurnRequest>()
        override suspend fun send(req: TurnRequest): JSONObject {
            sent += req
            val r = reply(req)
            return if (r is CompletableDeferred<*>) r.await() as JSONObject else r as JSONObject
        }
    }

    private fun turn(next: Int, input: String = "null", done: Boolean = false, msg: String? = null) = JSONObject(
        """{"messages":[${if (msg != null) """{"role":"nia","text":"$msg"}""" else ""}],"input":$input,"progress":{"answered":0,"total":1},"done":$done,"next_turn":$next}""",
    )
    private val probe = """{"id":"probe:nps:1","kind":"followup","title":"Why?","input":{"type":"free"},"required":false}"""

    private class Run(val host: FormHost, val results: MutableList<ExperienceResult>, val reviews: MutableList<String>)

    private fun TestScope.host(
        raw: JSONObject, src: Source = Source(), device: List<String> = listOf("en"),
        conversation: ConversationConfig? = null, wire: Wire? = null,
    ): Run {
        val results = mutableListOf<ExperienceResult>()
        val reviews = mutableListOf<String>()
        val handle = conversation?.let { ConversationHandle(it, wire!!, "conv-1", backgroundScope) }
        val cb = RenderCallbacks({}, {}, {}, "r", { reviews += it.name }, src.source, handle)
        return Run(FormHost(parseExperience(raw)!!, cb, { true }, device, backgroundScope) { results += it }, results, reviews)
    }

    private fun nps(v: Int) = Answer("nps", "nps", AnswerValue.Num(v))

    @Test fun plainFormReadsNoStorageAndSendsNoLanguage() = runTest {
        val src = Source(developer = "bn")
        val r = host(json(false), src, device = listOf("bn"))
        assertEquals(0, src.reads)
        assertFalse(r.host.language.decorated)
        r.host.finish()
        assertNull(r.results.single().response?.language)
    }

    @Test fun switchKeepsPageAndAnswers() = runTest {
        val src = Source()
        val h = host(json(true), src).host
        h.onChange("nps", nps(9))
        h.submitPage()
        assertEquals(1, h.state().pageIndex)
        h.switchLanguage("bn")
        assertEquals(1, h.state().pageIndex)
        assertEquals(nps(9), h.state().answers.single())
        assertEquals("bn csat", h.state().pageSteps.single().title)
        assertEquals(listOf("bn"), src.remembered)
        h.finish()
        assertEquals("bn", h.language.lang)
    }

    @Test fun switchResultCarriesTheLanguage() = runTest {
        val r = host(json(true))
        r.host.switchLanguage("bn")
        r.host.finish()
        assertEquals("bn", r.results.single().response?.language)
    }

    @Test fun switchKeepsInsertedProbe() = runTest {
        val wire = Wire { turn(1, probe) }
        val h = host(json(true, ai = true), conversation = ConversationConfig("enhanced", null, null, null, null), wire = wire).host
        h.onChange("nps", nps(3))
        h.submitPage()
        runCurrent()
        assertEquals("probe:nps:1", h.state().pageSteps.single().id)
        h.switchLanguage("ar")
        assertEquals("probe:nps:1", h.state().pageSteps.single().id)
        assertEquals("Why?", h.state().pageSteps.single().title)
    }

    @Test fun reviewShownOnceAcrossSwitch() = runTest {
        val review = ""","reviewRequest":{"enabled":true,"platform":"custom","reviewUrl":"https://example.com/r","trigger":{"rule":"always"}}"""
        val r = host(json(true, top = review))
        val h = r.host
        h.onChange("nps", nps(9)); h.submitPage()
        h.onChange("csat", Answer("csat", "csat", AnswerValue.Num(5))); h.submitPage()
        assertEquals(Phase.REVIEW, h.state().phase)
        h.review!!.onShown()
        h.switchLanguage("bn")
        assertEquals(Phase.REVIEW, h.state().phase)
        assertEquals(1, r.reviews.count { it == "\$review_request_shown" })
    }

    @Test fun probeLocaleOnlyForMulti() = runTest {
        val cfg = ConversationConfig("enhanced", null, null, null, null)
        val single = Wire { turn(1) }
        host(json(false, ai = true), conversation = cfg, wire = single).host.also { it.onChange("nps", nps(3)); it.submitPage() }
        runCurrent()
        assertNull(single.sent.single().locale)
        val multi = Wire { turn(1) }
        host(json(true, ai = true), device = listOf("ar"), conversation = cfg, wire = multi).host.also { it.onChange("nps", nps(3)); it.submitPage() }
        runCurrent()
        assertEquals("ar", multi.sent.single().locale)
    }

    @Test fun chatResultCarriesConversationIdAndLanguage() = runTest {
        val wire = Wire { if (it.action == "resume") turn(1, """{"id":"nps","kind":"nps","title":"Q"}""", msg = "hi") else turn(2, done = true) }
        val r = host(json(true), device = listOf("ar"), conversation = ConversationConfig("conversational", null, null, null, null), wire = wire)
        r.host.impression()
        runCurrent()
        r.host.sendChat(nps(9))
        runCurrent()
        assertTrue(r.host.chatState().done)
        advanceTimeBy(Defaults.THANKS_AUTO_CLOSE_MS + 1)
        runCurrent()
        val res = r.results.single()
        assertEquals("responded", res.outcome)
        assertEquals("conv-1", res.response?.conversationId)
        assertEquals("ar", res.response?.language)
    }

    @Test fun chatEndWhilePendingDismisses() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val wire = Wire { if (it.action == "resume") gate else turn(2, done = true) }
        val r = host(json(true), conversation = ConversationConfig("conversational", null, null, null, null), wire = wire)
        r.host.impression()
        runCurrent()
        assertTrue(r.host.chatState().pending)
        r.host.close()
        assertEquals("dismissed", r.results.single().outcome)
        gate.complete(turn(1))
        runCurrent()
        assertEquals("end", wire.sent.last().action)
    }

    @Test fun chatEndWhileAnswerInFlightCarriesFreshTurn() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val wire = Wire { if (it.action == "answer") gate else turn(1, probe) }
        val r = host(json(true), conversation = ConversationConfig("conversational", null, null, null, null), wire = wire)
        r.host.impression()
        runCurrent()
        r.host.sendChat(Answer("probe:nps:1", "followup", AnswerValue.Text("free", "hi")))
        runCurrent()
        r.host.close()
        gate.complete(turn(2))
        runCurrent()
        assertEquals(2, wire.sent.last { it.action == "end" }.turn)
    }
}
