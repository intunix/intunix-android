package com.intunix.sdk.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProbesTest {
    private fun nps(id: String, ai: Boolean = true) = Step(id, "nps", id, aiFollowUp = ai)
    private fun probe(id: String, kind: String = "followup", title: String = "Why?"): JSONObject =
        JSONObject("""{"id":"$id","kind":"$kind","title":"$title","input":{"type":"free"},"required":false}""")
    private fun reply(next: Int = 1, input: JSONObject? = null, busy: Boolean = false, done: Boolean = false) =
        JSONObject("""{"messages":[],"input":${input ?: "null"},"progress":{"answered":0,"total":0},"done":$done,"next_turn":$next${if (busy) ",\"busy\":true" else ""}}""")
    private fun ans(id: String, v: Int = 3) = Answer(id, "nps", AnswerValue.Num(v))
    private fun st(o: JSONObject) = parseStep(o)!!

    private val hang = Long.MAX_VALUE

    private class Fake(vararg script: Any) : ConversationTransport {
        val sent = mutableListOf<TurnRequest>()
        private val q = ArrayDeque(script.toList())
        override suspend fun send(req: TurnRequest): JSONObject {
            sent += req
            return when (val r = q.removeFirstOrNull() ?: JSONObject("""{"messages":[],"input":null,"progress":{"answered":0,"total":0},"done":false,"next_turn":1}""")) {
                is Throwable -> throw r
                is Long -> { delay(r); error("late") }
                else -> r as JSONObject
            }
        }
    }

    private val cfg = ConversationConfig("enhanced", null, null, null, null)
    private fun TestScope.probes(t: ConversationTransport, locale: String? = "en") =
        EnhancedProbes(ConversationHandle(cfg, t, "c1", backgroundScope), locale)

    @Test fun requestsAProbeOnlyForAiFollowUpSteps() = runTest {
        val t = Fake()
        probes(t).afterPage(listOf(nps("s1", false), nps("s2")), listOf(ans("s1"), ans("s2")))
        assertEquals(listOf(TurnRequest("c1", 0, "answer", ans("s2"), "en")), t.sent)
    }

    @Test fun doesNotRequestForAnAiFollowUpStepWithoutAnAnswer() = runTest {
        val t = Fake()
        assertEquals(emptyList<Any>(), probes(t).afterPage(listOf(nps("s1")), emptyList()).insert)
        assertEquals(0, t.sent.size)
    }

    @Test fun returnsTheProbeStepPairedWithItsAnchor() = runTest {
        val p = probe("probe:s1:1")
        assertEquals(listOf("s1" to st(p)), probes(Fake(reply(input = p))).afterPage(listOf(nps("s1")), listOf(ans("s1"))).insert)
    }

    @Test fun asksAgainAfterAProbeIsAnswered() = runTest {
        val t = Fake()
        val a = Answer("probe:s2:1", "followup", AnswerValue.Text("free", "x"))
        probes(t).afterPage(listOf(st(probe("probe:s2:1"))), listOf(a))
        assertEquals(1, t.sent.size)
        assertEquals("probe:s2:1", t.sent[0].answer?.stepId)
    }

    @Test fun sendsNextTurnFromThePreviousReply() = runTest {
        val t = Fake(reply(next = 4))
        val p = probes(t)
        p.afterPage(listOf(nps("s1")), listOf(ans("s1")))
        p.afterPage(listOf(nps("s1")), listOf(ans("s1", 5)))
        assertEquals(listOf(0, 4), t.sent.map { it.turn })
    }

    @Test fun resolvesEmptyWhenTheReplyHasNoInput() = runTest {
        assertEquals(emptyList<Any>(), probes(Fake(reply())).afterPage(listOf(nps("s1")), listOf(ans("s1"))).insert)
    }

    @Test fun timesOutAfter8sAndDisablesItself() = runTest {
        val t = Fake(hang)
        val p = probes(t)
        val s = listOf(nps("s1"))
        val out = async { p.afterPage(s, listOf(ans("s1"))) }
        advanceTimeBy(PROBE_TIMEOUT_MS); runCurrent()
        assertEquals(emptyList<Any>(), out.await().insert)
        assertEquals(emptyList<Any>(), p.afterPage(s, listOf(ans("s1", 9))).insert)
        assertEquals(1, t.sent.size)
    }

    @Test fun aRejectionDisablesItWithoutThrowing() = runTest {
        val t = Fake(RuntimeException("boom"))
        val p = probes(t)
        val s = listOf(nps("s1"))
        assertEquals(emptyList<Any>(), p.afterPage(s, listOf(ans("s1"))).insert)
        assertEquals(emptyList<Any>(), p.afterPage(s, listOf(ans("s1", 9))).insert)
        assertEquals(1, t.sent.size)
    }

    @Test fun retriesABusyReplyOnceUsingTheBusyNextTurn() = runTest {
        val p = probe("probe:s1:1")
        val t = Fake(reply(next = 2, busy = true), reply(next = 3, input = p))
        val out = async { probes(t).afterPage(listOf(nps("s1")), listOf(ans("s1"))) }
        runCurrent()
        assertEquals(1, t.sent.size)
        advanceTimeBy(700); runCurrent()
        assertEquals(listOf("s1" to st(p)), out.await().insert)
        assertEquals(listOf(0, 2), t.sent.map { it.turn })
    }

    @Test fun doesNotRetryASecondBusyReplyAndStaysEnabled() = runTest {
        val t = Fake(reply(busy = true), reply(busy = true))
        val p = probes(t)
        val s = listOf(nps("s1"))
        val out = async { p.afterPage(s, listOf(ans("s1"))) }
        advanceTimeBy(700); runCurrent()
        assertEquals(emptyList<Any>(), out.await().insert)
        p.afterPage(s, listOf(ans("s1", 9)))
        assertEquals(3, t.sent.size)
    }

    @Test fun ignoresALateReplyFromATimedOutRequest() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        val sent = mutableListOf<TurnRequest>()
        val p = probes(ConversationTransport { sent += it; gate.await() })
        val out = async { p.afterPage(listOf(nps("s1")), listOf(ans("s1"))) }
        advanceTimeBy(PROBE_TIMEOUT_MS); runCurrent()
        assertEquals(emptyList<Any>(), out.await().insert)
        gate.complete(reply(input = probe("probe:s1:1")))
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test fun asksSequentiallyOneRequestPerAnsweredStepInPageOrder() = runTest {
        val t = Fake(reply(next = 1), reply(next = 2))
        probes(t).afterPage(listOf(nps("a"), nps("b")), listOf(ans("b"), ans("a")))
        assertEquals(listOf("a" to 0, "b" to 1), t.sent.map { it.answer?.stepId to it.turn })
    }

    @Test fun doesNotAskTwiceForTheSameAnswerButDoesWhenItChanges() = runTest {
        val t = Fake()
        val p = probes(t)
        val s = listOf(nps("s1"))
        p.afterPage(s, listOf(ans("s1", 3)))
        p.afterPage(s, listOf(ans("s1", 3)))
        assertEquals(1, t.sent.size)
        p.afterPage(s, listOf(ans("s1", 4)))
        assertEquals(2, t.sent.size)
    }

    @Test fun ignoresInputsThatAreNotValidProbesOrAreDuplicates() = runTest {
        val good = probe("probe:s1:1")
        val p = probes(Fake(
            reply(input = probe("other:1")), reply(input = probe("probe:s1:2", kind = "nps").also { it.remove("input") }),
            reply(input = probe("probe:s1:3", title = "")), reply(input = good), reply(input = good),
        ))
        val s = listOf(nps("s1"))
        for (v in 1..3) assertEquals(emptyList<Any>(), p.afterPage(s, listOf(ans("s1", v))).insert)
        assertEquals(listOf("s1" to st(good)), p.afterPage(s, listOf(ans("s1", 4))).insert)
        assertEquals(emptyList<Any>(), p.afterPage(listOf(nps("s2")), listOf(ans("s2", 5))).insert) // same id from another step
    }

    @Test fun reportsDropWhenAnAnswerChangesAndReAsks() = runTest {
        val t = Fake()
        val p = probes(t)
        val s = listOf(nps("s1"))
        assertEquals(emptyList<String>(), p.afterPage(s, listOf(ans("s1", 3))).drop)
        assertEquals(emptyList<String>(), p.afterPage(s, listOf(ans("s1", 3))).drop)
        assertEquals(1, t.sent.size)
        assertEquals(listOf("s1"), p.afterPage(s, listOf(ans("s1", 4))).drop)
        assertEquals(2, t.sent.size)
    }

    @Test fun reportsDropWhenTheAnswerWasRemovedWithoutARequest() = runTest {
        val t = Fake()
        val p = probes(t)
        val s = listOf(nps("s1"))
        p.afterPage(s, listOf(ans("s1")))
        assertEquals(ProbeUpdate(listOf("s1"), emptyList()), p.afterPage(s, emptyList()))
        assertEquals(1, t.sent.size)
    }

    @Test fun acceptsAReIssuedProbeIdAfterADrop() = runTest {
        val p1 = probe("probe:s1:1")
        val p = probes(Fake(reply(input = p1), reply(input = p1)))
        val s = listOf(nps("s1"))
        p.afterPage(s, listOf(ans("s1", 3)))
        assertEquals(ProbeUpdate(listOf("s1"), listOf("s1" to st(p1))), p.afterPage(s, listOf(ans("s1", 4))))
    }

    @Test fun aDisabledHelperStillReportsDrop() = runTest {
        val p = probes(Fake(RuntimeException("boom")))
        val s = listOf(nps("s1"))
        p.afterPage(s, listOf(ans("s1", 3)))
        assertEquals(ProbeUpdate(listOf("s1"), emptyList()), p.afterPage(s, listOf(ans("s1", 4))))
    }

    @Test fun aTransportThatThrowsSynchronouslyDisablesWithoutRejecting() = runTest {
        val sent = mutableListOf<TurnRequest>()
        val p = probes(ConversationTransport { sent += it; throw IllegalStateException("sync") })
        val s = listOf(nps("s1"))
        assertEquals(emptyList<Any>(), p.afterPage(s, listOf(ans("s1"))).insert)
        p.afterPage(s, listOf(ans("s1", 9)))
        assertEquals(1, sent.size)
    }

    @Test fun clearsTheBusyRetryWaitWhenTheBudgetEndsFirst() = runTest {
        val t = Fake(reply(busy = true), hang)
        val out = async { probes(t).afterPage(listOf(nps("s1")), listOf(ans("s1"))) }
        advanceTimeBy(PROBE_TIMEOUT_MS); runCurrent()
        out.await()
        assertEquals(2, t.sent.size)
    }

    @Test fun treatsAnInvalidReplyAsAFailureDisabledNothingInserted() = runTest {
        val t = Fake(JSONObject("""{"messages":[],"done":false,"next_turn":1}"""))
        val p = probes(t)
        assertEquals(emptyList<Any>(), p.afterPage(listOf(nps("s1")), listOf(ans("s1"))).insert)
        p.afterPage(listOf(nps("s2")), listOf(ans("s2")))
        assertEquals(1, t.sent.size)
    }

    @Test fun stopsSendingAfterAReplyWithDoneTrue() = runTest {
        val t = Fake(reply(done = true))
        val p = probes(t)
        p.afterPage(listOf(nps("s1")), listOf(ans("s1")))
        assertEquals(emptyList<Any>(), p.afterPage(listOf(nps("s2")), listOf(ans("s2"))).insert)
        assertEquals(1, t.sent.size)
    }

    @Test fun waitsAtLeast10sBeforeGivingUp() = assertEquals(10_000L, PROBE_TIMEOUT_MS)

    @Test fun setLocaleLaterRequestsCarryTheNewLocale() = runTest {
        val t = Fake()
        val p = probes(t)
        p.setLocale("ar")
        p.afterPage(listOf(nps("s1")), listOf(ans("s1")))
        assertEquals("ar", t.sent[0].locale)
    }

    @Test fun aBusyRetryKeepsItsFirstLocaleTheNextRequestUsesTheNewOne() = runTest {
        val t = Fake(reply(next = 2, busy = true), reply(next = 3), reply(next = 4))
        val p = probes(t)
        val out = async { p.afterPage(listOf(nps("s1")), listOf(ans("s1"))) }
        runCurrent()
        p.setLocale("ar")
        advanceTimeBy(700); runCurrent()
        out.await()
        assertEquals(listOf("en", "en"), t.sent.map { it.locale })
        p.afterPage(listOf(nps("s2")), listOf(ans("s2")))
        assertEquals("ar", t.sent[2].locale)
    }

    @Test fun noLocaleWhenNull() = runTest {
        val t = Fake()
        probes(t, null).afterPage(listOf(nps("s1")), listOf(ans("s1")))
        assertTrue(t.sent[0].locale == null)
        assertFalse(t.sent[0].toJson().has("locale"))
    }

    @Test fun anUnbuildableInputIsSkippedWithoutDisablingAdvancingTheTurnAndHonouringDone() = runTest {
        val unknownKind = JSONObject("""{"id":"probe:s1:1","kind":"hologram","title":"?"}""")
        val unknownInput = JSONObject("""{"id":"probe:s1:2","kind":"followup","title":"?","input":{"type":"hologram"}}""")
        val good = probe("probe:s1:3")
        val t = Fake(reply(next = 2, input = unknownKind), reply(next = 3, input = unknownInput), reply(next = 4, input = good))
        val p = probes(t)
        val s = listOf(nps("s1"))
        assertEquals(emptyList<Any>(), p.afterPage(s, listOf(ans("s1", 1))).insert)
        assertEquals(emptyList<Any>(), p.afterPage(s, listOf(ans("s1", 2))).insert)
        assertEquals(listOf("s1" to st(good)), p.afterPage(s, listOf(ans("s1", 3))).insert)
        assertEquals(listOf(0, 2, 3), t.sent.map { it.turn })
        val d = Fake(reply(next = 2, input = unknownKind, done = true))
        val q = probes(d)
        q.afterPage(s, listOf(ans("s1", 1)))
        q.afterPage(listOf(nps("s2")), listOf(ans("s2")))
        assertEquals(1, d.sent.size)
    }
}
