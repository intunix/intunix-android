package com.intunix.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class Fake(vararg script: Any) : ConversationTransport {
    val sent = mutableListOf<TurnRequest>()
    private val q = ArrayDeque(script.toList())
    /** Queue another scripted outcome (used after the controller has started). */
    fun add(vararg more: Any) = q.addAll(more.toList())
    override suspend fun send(req: TurnRequest): JSONObject {
        sent += req
        return when (val r = q.removeFirst()) {
            is Throwable -> throw r
            is Long -> { delay(r); error("late") }
            is CompletableDeferred<*> -> r.await() as JSONObject
            else -> r as JSONObject
        }
    }
}

private fun reply(next: Int, vararg msgs: String, input: String? = null, done: Boolean = false, busy: Boolean = false, answered: Int = 0, total: Int = 3) =
    JSONObject("""{"messages":${JSONArray(msgs.map { JSONObject().put("role","nia").put("text",it) })},"input":${input ?: "null"},"progress":{"answered":$answered,"total":$total},"done":$done,"next_turn":$next${if (busy) ",\"busy\":true" else ""}}""")

private fun stepJson(id: String, extra: String = "", kind: String = "nps", title: String = "Q $id") =
    JSONObject("""{"id":"$id","kind":"$kind","title":"$title"$extra}""")
private fun nps(id: String, extra: String = "", title: String = "Q $id") = stepJson(id, extra, title = title)
private fun followup(id: String, required: Boolean, title: String = "Why") =
    stepJson(id, ""","required":$required,"input":{"type":"free"}""", "followup", title)
private fun content(steps: List<JSONObject>, thanksTitle: String? = null): FeedbackContent =
    parseFeedbackContent(JSONObject().put("kind", "feedback").put("brand", JSONObject().put("color", "#000"))
        .put("steps", JSONArray(steps)).also { o -> thanksTitle?.let { o.put("thanks", JSONObject().put("title", it)) } })!!
private fun step(o: JSONObject) = parseStep(o)!!
private fun nia(t: String) = ChatMessage("nia", t)
private val List<ChatMessage>.bare get() = map { it.copy(source = null) }
private val ChatMessage.bare get() = copy(source = null)
private fun a(id: String, v: Int) = Answer(id, "nps", AnswerValue.Num(v))
private fun free(id: String, t: String) = Answer(id, "followup", AnswerValue.Text("free", t))

private val S1j = nps("s1")
private val S2j = nps("s2", ""","showIf":{"stepId":"s1","op":"lte","value":6}""")
private val S3j = nps("s3")
private val S1 = step(S1j)
private val S3 = step(S3j)
private val FORM = content(listOf(S1j, S2j, S3j))
private val cfgC = ConversationConfig("conversational", null, null, null, null)

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerTest {
    private class Made(val ctl: ConversationController, val t: Fake)

    private fun TestScope.make(vararg script: Any, c: FeedbackContent = FORM, config: ConversationConfig = cfgC): Made {
        val t = Fake(*script)
        return Made(ConversationController(c, ConversationHandle(config, t, "cid-12345", backgroundScope), "en"), t)
    }
    private val Made.requests get() = t.sent
    private fun ConversationController.roles(r: String) = state().messages.filter { it.role == r }
    private fun ConversationController.texts() = state().messages.map { it.text }

    @Test fun startSendsResumeTurn0AndShowsTheServerMessagesAndFirstInput() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(gate)
        val p = async { m.ctl.start() }
        runCurrent()
        assertTrue(m.ctl.state().pending)
        gate.complete(reply(4, "Hi", input = S1j.toString()))
        p.await()
        assertEquals(listOf(TurnRequest("cid-12345", 0, "resume", null, "en")), m.requests)
        val s = m.ctl.state()
        assertEquals(listOf(nia("Hi")), s.messages.bare)
        assertEquals(S1, s.input)
        assertFalse(s.pending)
        assertFalse(s.local)
    }

    @Test fun restoresTheTranscriptOnResume() = runTest {
        val transcript = listOf(nia("Hi"), ChatMessage("respondent", "9"), nia("Why?"))
        val m = make(reply(5, "ignored", input = S3j.toString()).put("transcript", JSONArray(transcript.map { JSONObject().put("role", it.role).put("text", it.text) })))
        m.ctl.start()
        assertEquals(transcript, m.ctl.state().messages.bare)
    }

    @Test fun startIsANoOpTheSecondTimeAndADoneResumeShowsTheTranscript() = runTest {
        val transcript = listOf(nia("Hi"), nia("Bye"))
        val m = make(reply(1, done = true).put("transcript", JSONArray(transcript.map { JSONObject().put("role", it.role).put("text", it.text) })))
        m.ctl.start(); m.ctl.start()
        assertEquals(1, m.requests.size)
        val s = m.ctl.state()
        assertTrue(s.done); assertNull(s.input); assertEquals(transcript, s.messages.bare)
    }

    @Test fun answerAppendsTheRespondentBubbleSendsNextTurnThenShowsTheReply() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(reply(4, input = S1j.toString()), gate)
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        assertEquals(listOf(ChatMessage("respondent", "9")), m.ctl.roles("respondent").bare)
        assertTrue(m.ctl.state().pending)
        gate.complete(reply(5, "Thanks", input = S3j.toString(), answered = 1))
        p.await()
        assertEquals(TurnRequest("cid-12345", 4, "answer", a("s1", 9), "en"), m.requests[1])
        val s = m.ctl.state()
        assertEquals(listOf("9", "Thanks"), m.ctl.texts())
        assertEquals(S3, s.input)
        assertEquals(1 to 3, s.answered to s.total)
    }

    @Test fun ignoresAnAnswerWhilePending() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(2, input = S3j.toString()))
        m.ctl.start()
        val p1 = async { m.ctl.answer(a("s1", 9)) }
        val p2 = async { m.ctl.answer(a("s1", 3)) }
        awaitAll(p1, p2)
        assertEquals(1, m.requests.count { it.action == "answer" })
        assertEquals(1, m.ctl.roles("respondent").size)
        assertEquals(listOf(a("s1", 9)), m.ctl.result().answers)
    }

    @Test fun ignoresAnAnswerForAStepThatIsNotTheCurrentInput() = runTest {
        val m = make(reply(1, input = S1j.toString()))
        m.ctl.start()
        m.ctl.answer(a("s3", 5))
        assertEquals(1, m.requests.size)
        assertEquals(emptyList<ChatMessage>(), m.ctl.state().messages)
    }

    @Test fun ignoresAnInvalidAnswer() = runTest {
        val m = make(reply(1, input = followup("f", true).toString()))
        m.ctl.start()
        m.ctl.answer(free("f", "   "))
        m.ctl.answer(a("s1", 99))
        assertEquals(1, m.requests.size)
        assertEquals(emptyList<ChatMessage>(), m.ctl.state().messages)
        assertFalse(m.ctl.state().pending)
    }

    @Test fun retriesABusyReplyWithoutDuplicatingTheBubble() = runTest {
        val m = make(reply(4, input = S1j.toString()), reply(6, busy = true), reply(7, input = S3j.toString()))
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        assertEquals(2, m.requests.size)
        assertTrue(m.ctl.state().pending)
        advanceTimeBy(BUSY_RETRY_MS); runCurrent()
        p.await()
        assertEquals(3, m.requests.size)
        assertEquals(TurnRequest("cid-12345", 6, "answer", a("s1", 9), "en"), m.requests[2])
        assertEquals(1, m.ctl.roles("respondent").size)
        val s = m.ctl.state()
        assertFalse(s.pending); assertFalse(s.local); assertEquals(S3, s.input)
    }

    @Test fun givesUpAfterTwoBusyRetriesAndGoesLocal() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(2, busy = true), reply(3, busy = true), reply(4, busy = true))
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        advanceTimeBy(BUSY_RETRY_MS * 3); runCurrent()
        p.await()
        assertEquals(4, m.requests.size)
        assertTrue(m.ctl.state().local); assertFalse(m.ctl.state().pending)
        assertEquals("s3", m.ctl.state().input?.id)
    }

    @Test fun skipIsRefusedOnARequiredInputAndSentOnAnOptionalOne() = runTest {
        val opt = followup("f", false)
        val m = make(reply(1, input = S1j.toString()), reply(2, input = opt.toString()), reply(3, input = S3j.toString()))
        m.ctl.start()
        m.ctl.skip()
        assertEquals(1, m.requests.size)
        m.ctl.answer(a("s1", 9))
        m.ctl.skip()
        assertEquals("skip", m.requests[2].action); assertEquals(2, m.requests[2].turn)
        assertEquals(1, m.ctl.roles("respondent").size)
        assertEquals(S3, m.ctl.state().input)
    }

    @Test fun endMarksDoneAndEndedByRespondentEvenIfTheTransportRejects() = runTest {
        val m = make(reply(1, input = S1j.toString()), RuntimeException("down"))
        m.ctl.start()
        m.ctl.end()
        runCurrent()
        assertEquals("end", m.requests[1].action)
        val s = m.ctl.state()
        assertTrue(s.done); assertTrue(s.endedByRespondent); assertFalse(s.local)
        assertFalse(m.ctl.result().completed)
    }

    @Test fun endWhilePendingIsImmediateAndTheInFlightReplyIsIgnored() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(reply(1, input = S1j.toString()), gate, reply(1))
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        m.ctl.end()
        assertTrue(m.ctl.state().done); assertTrue(m.ctl.state().endedByRespondent)
        gate.complete(reply(9, "late", input = S3j.toString()))
        p.await()
        runCurrent()
        assertEquals(listOf("resume", "answer", "end"), m.requests.map { it.action })
        val s = m.ctl.state()
        assertFalse(m.ctl.texts().contains("late"))
        assertTrue(s.done); assertNull(s.input); assertFalse(s.pending)
    }

    @Test fun transportFailureMidConversationContinuesLocallyWithTheNextVisiblePredefinedStep() = runTest {
        val m = make(reply(1, input = S1j.toString()), RuntimeException("down"))
        m.ctl.start()
        m.ctl.answer(a("s1", 9))
        val s = m.ctl.state()
        assertTrue(s.local)
        assertEquals(S3, s.input) // s2 hidden by showIf
        assertEquals(listOf("9", "Q s3"), m.ctl.texts())
        assertEquals(1 to 2, s.answered to s.total)
        m.ctl.answer(a("s3", 5))
        m.ctl.end()
        runCurrent()
        assertEquals(2, m.requests.size)
    }

    @Test fun goingLocalAfterASkippedProbeDropsItAndDoesNotRepeatAQuestionAlreadyShown() = runTest {
        val probe = stepJson("probe:s1:1", ""","input":{"type":"free"},"required":false""", "followup", "Q s1")
        val m = make(reply(1, "Q s1", input = probe.toString()), RuntimeException("down"))
        m.ctl.start()
        m.ctl.skip()
        assertTrue(m.ctl.state().local); assertEquals(S1, m.ctl.state().input)
        assertEquals(listOf(nia("Q s1")), m.ctl.state().messages.bare)
    }

    @Test fun transportFailureOnStartRunsTheWholeFormLocallyWelcomeFirst() = runTest {
        val m = make(RuntimeException("down"), config = ConversationConfig("conversational", null, "Welcome", null, null))
        m.ctl.start()
        val s = m.ctl.state()
        assertTrue(s.local); assertFalse(s.pending); assertFalse(s.done)
        assertEquals(listOf(nia("Welcome"), nia("Q s1")), s.messages.bare)
        assertEquals(S1, s.input)
        assertEquals(0 to 2, s.answered to s.total)
        assertEquals(1, m.requests.size)
    }

    @Test fun localModeFinishesWithTheCompletionMessageAndDone() = runTest {
        val m = make(RuntimeException("down"), c = content(listOf(S1j), "Cheers"))
        m.ctl.start()
        m.ctl.answer(a("s1", 8))
        val s = m.ctl.state()
        assertTrue(s.done); assertNull(s.input); assertTrue(s.local)
        assertEquals(nia("Cheers"), s.messages.last().bare)
        assertTrue(m.ctl.result().completed)
        val c = make(RuntimeException("down"), c = content(listOf(S1j)), config = ConversationConfig("conversational", null, null, "All done", null))
        c.ctl.start()
        c.ctl.answer(a("s1", 8))
        assertEquals(nia("All done"), c.ctl.state().messages.last().bare)
    }

    @Test fun localSkipMarksTheStepSkippedAndMovesOnRequiredCannotBeSkipped() = runTest {
        val m = make(RuntimeException("down"), c = content(listOf(followup("o", false, "Opt"), S1j)))
        m.ctl.start()
        m.ctl.skip()
        assertEquals(S1, m.ctl.state().input)
        m.ctl.skip()
        assertEquals(S1, m.ctl.state().input)
    }

    @Test fun resultCarriesAnswersConversationIdAndCompleted() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(1, input = S3j.toString()), reply(1, done = true))
        m.ctl.start()
        m.ctl.answer(a("s1", 9))
        m.ctl.answer(a("s3", 7))
        val r = m.ctl.result()
        assertEquals(listOf(a("s1", 9), a("s3", 7)), r.answers)
        assertTrue(r.completed); assertEquals("cid-12345", r.conversationId); assertTrue(r.durationMs >= 0)
        val b = make(reply(1, input = S1j.toString()))
        b.ctl.start()
        b.ctl.end()
        assertFalse(b.ctl.result().completed)
    }

    @Test fun acceptsAnInputStepTheFormDoesNotKnowAndValidatesAgainstIt() = runTest {
        val extra = stepJson("x1", ""","max":7""", "star", "Stars")
        val m = make(reply(1, input = extra.toString()), reply(1, done = true))
        m.ctl.start()
        m.ctl.answer(Answer("x1", "star", AnswerValue.Num(8)))
        assertEquals(1, m.requests.size)
        m.ctl.answer(Answer("x1", "star", AnswerValue.Num(7)))
        assertEquals(2, m.requests.size)
        assertEquals(1, m.ctl.result().answers.size)
    }

    @Test fun storesAProbeAnswerUnderItsProbeId() = runTest {
        val probe = stepJson("probe:s1:1", ""","input":{"type":"free"},"required":false""", "followup", "Why?")
        val m = make(reply(1, input = probe.toString()), reply(1, input = S3j.toString()))
        m.ctl.start()
        m.ctl.answer(free("probe:s1:1", "slow app"))
        assertEquals(free("probe:s1:1", "slow app"), m.requests[1].answer)
        assertEquals(listOf(free("probe:s1:1", "slow app")), m.ctl.result().answers)
        assertEquals(ChatMessage("respondent", "slow app"), m.ctl.state().messages[0])
    }

    @Test fun notifiesSubscribersAndStopsAfterUnsubscribe() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(1, input = S3j.toString()))
        var n = 0
        val off = m.ctl.subscribe { n++ }
        m.ctl.start()
        assertTrue(n > 0)
        off()
        n = 0
        m.ctl.answer(a("s1", 9))
        assertEquals(0, n)
    }

    // ---- hardening ---------------------------------------------------------

    private val opt get() = followup("f", false)
    private val reqd get() = followup("f", true)

    @Test fun aBlankAnswerOnAnOptionalStepIsASkipNoBubbleNoStoredAnswerFalse() = runTest {
        val m = make(reply(1, input = opt.toString()), reply(2, input = S3j.toString()))
        m.ctl.start()
        assertFalse(m.ctl.answer(free("f", "  ")))
        assertEquals("skip", m.requests[1].action); assertEquals(1, m.requests[1].turn)
        assertNull(m.requests[1].answer)
        assertEquals(emptyList<ChatMessage>(), m.ctl.state().messages)
        assertEquals(emptyList<Answer>(), m.ctl.result().answers)
    }

    @Test fun aBlankAnswerOnARequiredStepSendsNothing() = runTest {
        val m = make(reply(1, input = reqd.toString()))
        m.ctl.start()
        assertFalse(m.ctl.answer(free("f", "")))
        assertEquals(1, m.requests.size)
    }

    @Test fun canSkipTracksOptionalRequiredPendingAndDone() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(reply(1, input = opt.toString()), gate, reply(3, input = opt.toString()))
        assertFalse(m.ctl.state().canSkip)
        m.ctl.start()
        assertTrue(m.ctl.state().canSkip)
        val p = async { m.ctl.skip() }
        runCurrent()
        assertFalse(m.ctl.state().canSkip) // pending
        gate.complete(reply(2, input = S1j.toString()))
        p.await()
        assertEquals(S1, m.ctl.state().input)
        assertFalse(m.ctl.state().canSkip) // nps, no `required`
        m.ctl.answer(a("s1", 5))
        m.ctl.end()
        assertFalse(m.ctl.state().canSkip) // done
    }

    @Test fun answerResolvesTrueWhenAcceptedAndFalseForEveryIgnoreReason() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(1, done = true))
        assertFalse(m.ctl.answer(a("s1", 9))) // no input
        m.ctl.start()
        assertFalse(m.ctl.answer(a("s3", 9))) // wrong step
        assertFalse(m.ctl.answer(a("s1", 99))) // invalid
        val first = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        assertFalse(m.ctl.answer(a("s1", 8))) // pending
        assertTrue(first.await())
        assertFalse(m.ctl.answer(a("s1", 9))) // done
    }

    @Test fun aThrowingSubscriberNeitherStopsTheOthersNorLeavesPendingSet() = runTest {
        val m = make(reply(1, input = S1j.toString()))
        var ok = 0
        m.ctl.subscribe { throw IllegalStateException("boom") }
        m.ctl.subscribe { ok++ }
        m.ctl.start()
        assertTrue(ok > 0)
        assertFalse(m.ctl.state().pending)
    }

    @Test fun aSynchronouslyThrowingTransportGoesLocalWithoutRejecting() = runTest {
        val ctl = ConversationController(FORM, ConversationHandle(cfgC, { throw IllegalStateException("sync") }, "cid-12345", backgroundScope), null)
        ctl.start()
        assertTrue(ctl.state().local); assertFalse(ctl.state().pending)
        ctl.end()
    }

    @Test fun aMalformedReplyGoesLocal() = runTest {
        val m = make(JSONObject("""{"input":${S1j},"next_turn":1}"""))
        m.ctl.start()
        assertTrue(m.ctl.state().local); assertFalse(m.ctl.state().pending)
        assertEquals(S1, m.ctl.state().input)
    }

    @Test fun aTransportThatNeverSettlesGoesLocalAfterTURN_TIMEOUT_MS() = runTest {
        val m = make(Long.MAX_VALUE)
        val p = async { m.ctl.start() }
        advanceTimeBy(TURN_TIMEOUT_MS - 1); runCurrent()
        assertFalse(m.ctl.state().local)
        advanceTimeBy(1); runCurrent()
        p.await()
        assertTrue(m.ctl.state().local); assertFalse(m.ctl.state().pending)
    }

    @Test fun endDuringAnInFlightRequestIsSentAfterItSettlesWithTheLatestTurn() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(reply(1, input = S1j.toString()), gate, reply(1))
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        m.ctl.end()
        runCurrent()
        assertEquals(listOf("resume", "answer"), m.requests.map { it.action })
        gate.complete(reply(7))
        p.await()
        runCurrent()
        assertEquals(listOf("resume", "answer", "end"), m.requests.map { it.action })
        assertEquals(7, m.requests[2].turn)
    }

    @Test fun aResumeReplySeedsAnswersAndSkippedLocalModeDoesNotAskThemAgain() = runTest {
        val probe = stepJson("probe:s1:1", ""","input":{"type":"free"},"required":false""", "followup", "More?")
        val m = make(
            reply(3, input = probe.toString())
                .put("answers", JSONArray("""[{"stepId":"s1","kind":"nps","value":9},{"stepId":"zz","kind":"nps","value":1},{"stepId":"s3","kind":"nps","value":99}]"""))
                .put("skipped", JSONArray("""["s3"]""")),
            RuntimeException("down"),
        )
        m.ctl.start()
        assertEquals(listOf(a("s1", 9)), m.ctl.result().answers)
        m.ctl.skip() // fails -> local: s1 answered, s2 hidden, s3 skipped
        val s = m.ctl.state()
        assertTrue(s.local); assertTrue(s.done); assertNull(s.input)
    }

    @Test fun aSecondStepWithTheSameTitleAsASkippedOneStillShowsItsQuestion() = runTest {
        val twin = listOf(followup("o", false, "Same?"), followup("p", false, "Same?"))
        val m = make(RuntimeException("down"), c = content(twin))
        m.ctl.start()
        m.ctl.skip()
        assertEquals("p", m.ctl.state().input?.id)
        assertEquals(2, m.ctl.state().messages.count { it.text == "Same?" })
    }

    @Test fun aMalformedSeededAnswerIsDroppedWithoutGoingLocalTheLastEntryPerStepWins() = runTest {
        val m = make(reply(2, input = S3j.toString()).put("answers", JSONArray(
            """[{"stepId":"s1","kind":"nps","value":9},{"stepId":"s2","kind":"followup"},null,{"stepId":"s1","kind":"nps","value":7}]""")))
        m.ctl.start()
        assertFalse(m.ctl.state().local)
        assertEquals(listOf(a("s1", 7)), m.ctl.result().answers)
    }

    @Test fun aMalformedAnswerFromARendererResolvesFalseAndSendsNothing() = runTest {
        val m = make(reply(1, input = opt.toString()))
        m.ctl.start()
        assertFalse(m.ctl.answer(Answer("f", "followup", AnswerValue.Num(1))))
        assertEquals(1, m.requests.size)
        assertEquals(emptyList<ChatMessage>(), m.ctl.state().messages)
        assertFalse(m.ctl.state().pending)
    }

    @Test fun endResolvesAtOnceWhenTheInFlightRequestHangsAndSendsNoEndAfterTheTimeout() = runTest {
        val m = make(reply(1, input = S1j.toString()), Long.MAX_VALUE)
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        m.ctl.end()
        assertTrue(m.ctl.state().done); assertTrue(m.ctl.state().endedByRespondent)
        advanceTimeBy(TURN_TIMEOUT_MS); runCurrent()
        p.await()
        assertEquals(listOf("resume", "answer"), m.requests.map { it.action })
        val s = m.ctl.state()
        assertTrue(s.done); assertTrue(s.endedByRespondent); assertFalse(s.local)
    }

    // ---- dispose -----------------------------------------------------------

    @Test fun disposeIgnoresAReplyThatArrivesLaterNoStateChangeNoNotificationNoRequest() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(reply(1, input = S1j.toString()), gate)
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        var n = 0
        m.ctl.subscribe { n++ }
        val before = m.ctl.state()
        m.ctl.dispose()
        gate.complete(reply(5, "late", input = S3j.toString()))
        p.await()
        runCurrent()
        assertSame(before, m.ctl.state())
        assertEquals(0, n)
        assertEquals(listOf("resume", "answer"), m.requests.map { it.action })
    }

    @Test fun disposeStopsABusyRetryWaitAndLeavesNoTimers() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(2, busy = true), reply(1, input = S3j.toString()))
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        m.ctl.dispose()
        p.await()
        advanceTimeBy(BUSY_RETRY_MS * 3); runCurrent()
        assertEquals(2, m.requests.size)
    }

    @Test fun disposeSendsNoEndMakesLaterCallsNoOpsKeepsResultAndIsIdempotent() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(2, input = S3j.toString()))
        m.ctl.start()
        m.ctl.answer(a("s1", 9))
        m.ctl.dispose()
        m.ctl.dispose()
        m.ctl.end()
        assertFalse(m.ctl.answer(a("s3", 5)))
        m.ctl.skip()
        m.ctl.start()
        runCurrent()
        assertEquals(listOf("resume", "answer"), m.requests.map { it.action })
        assertFalse(m.ctl.state().done); assertFalse(m.ctl.state().endedByRespondent)
        val r = m.ctl.result()
        assertEquals(listOf(a("s1", 9)), r.answers); assertFalse(r.completed)
    }

    @Test fun afterEndStillDeliversOneEndRequestAfterTheInFlightTurnWithItsNextTurn() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(reply(1, input = S1j.toString()), gate, reply(1))
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        var n = 0
        m.ctl.subscribe { n++ }
        m.ctl.end()
        n = 0
        m.ctl.dispose()
        assertEquals(listOf("resume", "answer"), m.requests.map { it.action })
        gate.complete(reply(8, "late"))
        p.await()
        runCurrent()
        assertEquals(listOf("resume", "answer", "end"), m.requests.map { it.action })
        assertEquals(8, m.requests[2].turn)
        assertEquals(0, n)
    }

    @Test fun afterEndSendsTheEndRequestOnceWhenNothingIsInFlight() = runTest {
        val m = make(reply(3, input = S1j.toString()), reply(1))
        m.ctl.start()
        m.ctl.end()
        m.ctl.dispose()
        runCurrent()
        assertEquals(listOf("resume", "end"), m.requests.map { it.action })
        assertEquals(3, m.requests[1].turn)
    }

    @Test fun afterEndSendsNoEndWhenTheInFlightRequestRejects() = runTest {
        val gate = CompletableDeferred<JSONObject>()
        val m = make(reply(1, input = S1j.toString()), gate)
        m.ctl.start()
        val p = async { m.ctl.answer(a("s1", 9)) }
        runCurrent()
        m.ctl.end()
        m.ctl.dispose()
        gate.completeExceptionally(RuntimeException("down"))
        assertTrue(p.await())
        runCurrent()
        assertEquals(listOf("resume", "answer"), m.requests.map { it.action })
    }

    @Test fun goesLocalOnAReplyTheTransportShouldNotHaveSentNoProgress() = runTest {
        val m = make(JSONObject("""{"messages":[],"done":false,"next_turn":1}"""))
        m.ctl.start()
        assertTrue(m.ctl.state().local)
        assertEquals("s1", m.ctl.state().input?.id)
    }

    // ---- setLanguage ---------------------------------------------------------

    @Test fun setLanguageLaterRequestsCarryTheNewLocale() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(1, input = S3j.toString()))
        m.ctl.start()
        m.ctl.setLanguage("bn", FORM, null)
        m.ctl.answer(a("s1", 9))
        assertEquals("bn", m.requests[1].locale)
    }

    @Test fun setLanguageLocalModeAsksTheNextQuestionFromTheNewContent() = runTest {
        val m = make(reply(1, input = S1j.toString()), RuntimeException("down"))
        m.ctl.start()
        m.ctl.setLanguage("bn", content(listOf(S1j, S2j, nps("s3", title = "বাংলা"))), null)
        m.ctl.answer(a("s1", 9))
        val s = m.ctl.state()
        assertTrue(s.local); assertEquals("বাংলা", s.input?.title); assertEquals("বাংলা", s.messages.last().text)
    }

    @Test fun setLanguageLocalModeEndsWithTheCompletionMessageOfTheNewConfig() = runTest {
        val m = make(RuntimeException("down"), c = content(listOf(S1j)), config = ConversationConfig("conversational", null, null, "All done", null))
        m.ctl.start()
        m.ctl.setLanguage("bn", content(listOf(S1j)), ConversationConfig("conversational", null, null, "সব শেষ", null))
        m.ctl.answer(a("s1", 8))
        assertEquals(nia("সব শেষ"), m.ctl.state().messages.last().bare)
    }

    private val BN = content(listOf(nps("s1", title = "প্রথম"), nps("s3", title = "তৃতীয়")))
    private val cfgBn = ConversationConfig("conversational", null, "স্বাগতম", "শেষ", null)
    private val cfgEn = ConversationConfig("conversational", null, "Welcome", "Done", null)
    private val probeJ = followup("probe:1", true, "Why?").toString()

    @Test fun setLanguageServerInputWithAPredefinedIdIsSwappedProbesStay() = runTest {
        val m = make(reply(1, input = S1j.toString()), reply(2, input = probeJ))
        m.ctl.start()
        m.ctl.setLanguage("bn", content(listOf(nps("s1", title = "নতুন"))), null)
        assertEquals("নতুন", m.ctl.state().input?.title)
        m.ctl.answer(a("s1", 9))
        val probe = m.ctl.state().input
        m.ctl.setLanguage("en", content(listOf(S1j)), null)
        assertSame(probe, m.ctl.state().input)
    }

    @Test fun setLanguageWelcomeQuestionsAndAnswersReRenderProbesStay() = runTest {
        val m = make(
            reply(1, "Welcome", "Q s1", input = S1j.toString()),
            reply(2, "Why 9?", input = probeJ),
            reply(3, "Q s3", input = S3j.toString()),
            c = content(listOf(S1j, S3j)), config = cfgEn,
        )
        m.ctl.start()
        m.ctl.answer(a("s1", 9))
        m.ctl.answer(free("probe:1", "because"))
        m.ctl.setLanguage("bn", BN, cfgBn)
        assertEquals(listOf("স্বাগতম", "প্রথম", "9", "Why 9?", "because", "তৃতীয়"), m.ctl.texts())
        assertEquals("তৃতীয়", m.ctl.state().input?.title)
    }

    @Test fun setLanguageAChoiceAnswerTakesItsLabelFromTheNewLanguage() = runTest {
        fun csat(l: String) = stepJson("s1", ""","labels":$l""", "csat", "Q")
        val en = csat("""["Bad","Ok","Good","Great","Best"]""")
        val m = make(reply(1, "Q", input = en.toString()), reply(2), c = content(listOf(en)))
        m.ctl.start()
        m.ctl.answer(Answer("s1", "csat", AnswerValue.Num(2)))
        assertEquals("Ok", m.ctl.texts().last())
        m.ctl.setLanguage("bn", content(listOf(csat("""["খারাপ","ঠিক","ভালো","দারুণ","সেরা"]"""))), null)
        assertEquals("ঠিক", m.ctl.texts().last())
    }

    @Test fun setLanguageTheCompletionMessageReRenders() = runTest {
        val m = make(RuntimeException("down"), c = content(listOf(S1j)), config = cfgEn)
        m.ctl.start()
        m.ctl.answer(a("s1", 8))
        assertEquals("Done", m.ctl.texts().last())
        m.ctl.setLanguage("bn", content(listOf(S1j)), cfgBn)
        assertEquals("শেষ", m.ctl.texts().last())
    }

    @Test fun setLanguageARestoredTranscriptIsTaggedSoItReRendersToo() = runTest {
        val tr = listOf(nia("Welcome"), nia("Q s1"), ChatMessage("respondent", "9"), nia("Why?"))
        val m = make(
            reply(1, input = S3j.toString()).put("transcript", JSONArray(tr.map { JSONObject().put("role", it.role).put("text", it.text) }))
                .put("answers", JSONArray().put(JSONObject("""{"stepId":"s1","kind":"nps","value":9}"""))),
            c = content(listOf(S1j, S3j)), config = cfgEn,
        )
        m.ctl.start()
        m.ctl.setLanguage("bn", BN, cfgBn)
        assertEquals(listOf("স্বাগতম", "প্রথম", "9", "Why?"), m.ctl.texts())
    }

    private suspend fun TestScope.localOn(c: FeedbackContent): ConversationController {
        val m = make(reply(1, input = S1j.toString()), RuntimeException("down"), c = c)
        m.ctl.start()
        m.ctl.answer(a("s1", 9))
        return m.ctl
    }

    @Test fun setLanguageInLocalModeSwapsTheCurrentPredefinedStepOnce() = runTest {
        val ctl = localOn(content(listOf(S1j, S3j)))
        assertEquals("s3", ctl.state().input?.id)
        var n = 0
        ctl.subscribe { n++ }
        ctl.setLanguage("bn", content(listOf(S1j, nps("s3", title = "তৃতীয়"))), null)
        assertEquals("তৃতীয়", ctl.state().input?.title)
        assertEquals(1, n)
    }

    @Test fun setLanguageInLocalModeKeepsTheInputWhenTheNewContentLacksThatStep() = runTest {
        val ctl = localOn(content(listOf(S1j, S3j)))
        val before = ctl.state().input
        var n = 0
        ctl.subscribe { n++ }
        ctl.setLanguage("bn", content(listOf(S1j)), null)
        assertSame(before, ctl.state().input)
        assertEquals(0, n)
    }

    @Test fun setLanguageCanSkipFollowsTheSwappedInStep() = runTest {
        val ctl = localOn(content(listOf(S1j, nps("s3", ""","required":true"""))))
        assertFalse(ctl.state().canSkip)
        ctl.setLanguage("bn", content(listOf(S1j, nps("s3", ""","required":false"""))), null)
        assertTrue(ctl.state().canSkip)
    }

    @Test fun setLanguageNoNotificationAndNoThrowAfterDisposeOrWhenDone() = runTest {
        val ctl = localOn(content(listOf(S1j, S3j)))
        var n = 0
        ctl.subscribe { n++ }
        ctl.dispose()
        ctl.setLanguage("bn", content(listOf(S1j, S3j)), null)
        assertEquals(0, n)
        val done = localOn(content(listOf(S1j)))
        assertTrue(done.state().done)
        var n2 = 0
        done.subscribe { n2++ }
        done.setLanguage("bn", content(listOf(S1j)), null)
        assertEquals(0, n2)
    }

    // ---- native-only -------------------------------------------------------

    @Test fun unparseableInputGoesLocal() = runTest {
        val t = Fake(reply(1, "Hi", input = """{"id":"x","kind":"hologram","title":"?"}"""))
        val c = ConversationController(FORM, ConversationHandle(cfgC, t, "conv-1", backgroundScope), null)
        c.start()
        assertTrue(c.state().local)
        assertEquals("s1", c.state().input?.id) // first predefined step
        c.answer(a("s1", 9))
        assertEquals(1, t.sent.size) // never called again
    }

    @Test fun localeSentOnEveryRequestAndChangesAfterSetLanguage() = runTest {
        val t = Fake(reply(1, "Hi", input = S1j.toString()), reply(2, "Ok", input = S3j.toString()), reply(3, done = true))
        val c = ConversationController(FORM, ConversationHandle(cfgC, t, "conv-1", backgroundScope), "bn")
        c.start()
        assertEquals("bn", t.sent[0].locale)
        c.setLanguage("ar", FORM, null)
        c.answer(a("s1", 9))
        assertEquals("ar", t.sent[1].locale)
        assertEquals(listOf("Hi", "9", "Ok"), c.state().messages.map { it.text }) // earlier messages unchanged
    }

    @Test fun setLanguageReplacesTheHandleConfigWhenGiven() = runTest {
        val m = make(RuntimeException("down"), c = content(listOf(S1j)))
        m.ctl.start()
        m.ctl.setLanguage("bn", content(listOf(S1j)), ConversationConfig("conversational", null, null, "bn-done", null))
        m.ctl.answer(a("s1", 1))
        assertEquals("bn-done", m.ctl.state().messages.last().text)
    }
}
