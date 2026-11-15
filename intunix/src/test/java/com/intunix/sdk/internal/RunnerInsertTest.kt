package com.intunix.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerInsertTest {
    private fun nps(id: String, title: String = id, extra: String = "") =
        parseStep(JSONObject("""{"id":"$id","kind":"nps","title":"$title"$extra}"""))!!
    private fun probe(id: String = "probe:s1:1") =
        parseStep(JSONObject("""{"id":"$id","kind":"followup","title":"Why?","input":{"type":"free"},"required":false}"""))!!
    private fun probeAnswer(id: String = "probe:s1:1") = Answer(id, "followup", AnswerValue.Text("free", "because"))
    private fun score(id: String, v: Int) = Answer(id, "nps", AnswerValue.Num(v))
    private fun content(vararg steps: Step) = FeedbackContent(steps.toList(), Brand("#2F9461", null))
    private fun ids(s: List<Step>) = s.map { it.id }
    private fun runner(vararg steps: Step) = FeedbackRunner(content(*steps), null)

    @Test fun insertsAProbeAsTheNextPageAndAdvancesOntoIt() {
        val r = runner(nps("s1"), nps("s2"))
        r.record(score("s1", 3))
        r.insertAfter("s1", probe())
        assertEquals("probe:s1:1", r.submitPage().pageSteps[0].id)
        assertEquals("s2", r.answer(probeAnswer()).pageSteps[0].id)
    }

    @Test fun doesNotMutateTheContentPassedIn() {
        val c = content(nps("s1"), nps("s2"))
        FeedbackRunner(c, null).insertAfter("s1", probe())
        assertEquals(listOf("s1", "s2"), ids(c.steps))
    }

    @Test fun ignoresAnUnknownAnchorAndADuplicateId() {
        val r = runner(nps("s1"), nps("s2"))
        assertEquals(listOf("s1", "s2"), ids(r.insertAfter("nope", probe()).visibleSteps))
        r.insertAfter("s1", probe())
        assertEquals(listOf("s1", "probe:s1:1", "s2"), ids(r.insertAfter("s2", probe()).visibleSteps))
    }

    @Test fun keepsTheProbeAnswerInFinishAnswers() {
        val r = runner(nps("s1"))
        r.record(score("s1", 3))
        r.insertAfter("s1", probe())
        r.submitPage()
        r.answer(probeAnswer())
        assertEquals(listOf("s1", "probe:s1:1"), r.finish().answers.map { it.stepId })
    }

    @Test fun insertsAfterTheLastStepOfAGroupedPageAndKeepsPagedUnchanged() {
        val r = runner(nps("a", extra = ""","page":1"""), nps("b", extra = ""","page":1"""), nps("c", extra = ""","page":2"""))
        val s = r.insertAfter("a", probe("probe:a:1"))
        assertEquals(listOf("a", "b", "probe:a:1", "c"), ids(s.visibleSteps))
        assertTrue(s.paged)
        assertEquals(listOf("a", "b"), ids(s.pageSteps))
        assertFalse(runner(nps("a"), nps("b")).insertAfter("a", probe("probe:a:1")).paged)
    }

    @Test fun worksWhenTheAnchorIsOnAnEarlierPage() {
        val r = runner(nps("s1"), nps("s2"), nps("s3"))
        r.record(score("s1", 3))
        r.submitPage()
        r.insertAfter("s1", probe())
        assertEquals(listOf("s1", "probe:s1:1", "s2", "s3"), ids(r.state().visibleSteps))
        assertEquals("s2", r.state().pageSteps[0].id)
    }

    @Test fun dropsTheProbeAndItsAnswerWhenTheAnchorBecomesHidden() {
        val gated = nps("s2", extra = ""","showIf":{"stepId":"s1","op":"gte","value":7}""")
        val r = runner(nps("s1"), gated, nps("s3"))
        r.record(score("s1", 9))
        r.submitPage()
        r.record(score("s2", 5))
        r.insertAfter("s2", probe("probe:s2:1"))
        r.submitPage()
        r.record(probeAnswer("probe:s2:1"))
        r.back(); r.back()
        val s = r.record(score("s1", 2))
        assertEquals(listOf("s1", "s3"), ids(s.visibleSteps))
        assertEquals(listOf("s1"), s.answers.map { it.stepId })
    }

    @Test fun backFromAProbeReturnsToTheAnchorAndForwardShowsTheSameProbe() {
        val r = runner(nps("s1"), nps("s2"))
        r.record(score("s1", 3))
        r.insertAfter("s1", probe())
        r.submitPage()
        assertEquals("s1", r.back().pageSteps[0].id)
        assertEquals("probe:s1:1", r.submitPage().pageSteps[0].id)
    }

    @Test fun putsASecondProbeAfterTheFirstAlsoOnAGroupedPage() {
        val r = runner(nps("s1"), nps("s2"))
        r.insertAfter("s1", probe("probe:s1:1"))
        assertEquals(listOf("s1", "probe:s1:1", "probe:s1:2", "s2"), ids(r.insertAfter("s1", probe("probe:s1:2")).visibleSteps))
        val g = runner(nps("a", extra = ""","page":1"""), nps("b", extra = ""","page":1"""), nps("c", extra = ""","page":2"""))
        g.insertAfter("a", probe("probe:a:1"))
        assertEquals(listOf("a", "b", "probe:a:1", "probe:a:2", "c"), ids(g.insertAfter("a", probe("probe:a:2")).visibleSteps))
    }

    @Test fun removeInsertedAfterRemovesChainedProbesAndTheirAnswersKeepingThePage() {
        val r = runner(nps("s1"), nps("s2"))
        r.record(score("s1", 3))
        r.insertAfter("s1", probe("probe:s1:1"))
        r.submitPage()
        r.record(probeAnswer("probe:s1:1"))
        r.insertAfter("probe:s1:1", probe("probe:probe:s1:1:1"))
        r.submitPage()
        r.record(probeAnswer("probe:probe:s1:1:1"))
        r.submitPage()
        r.record(score("s2", 4))
        val s = r.removeInsertedAfter("s1")
        assertEquals(listOf("s1", "s2"), ids(s.visibleSteps))
        assertEquals("s2", s.pageSteps[0].id)
        assertEquals(listOf("s1", "s2"), r.finish().answers.map { it.stepId })
        assertEquals(listOf("s1"), ids(runner(nps("s1")).removeInsertedAfter("s1").visibleSteps))
    }

    @Test fun aProbeDoesNotComeBackWhenItsAnchorIsHiddenThenShownAgain() {
        val gated = nps("s2", extra = ""","showIf":{"stepId":"s1","op":"gte","value":7}""")
        val r = runner(nps("s1"), gated)
        r.record(score("s1", 9))
        r.submitPage()
        r.insertAfter("s2", probe("probe:s2:1"))
        r.back()
        r.record(score("s1", 2))
        val s = r.record(score("s1", 9))
        assertEquals(listOf("s1", "s2"), ids(s.visibleSteps))
    }

    @Test fun insertAfterIsANoOpOnceTheRunnerLeftTheRunningPhase() {
        val r = runner(nps("s1"))
        r.answer(score("s1", 3))
        assertTrue(r.state().phase != Phase.RUNNING)
        assertEquals(listOf("s1"), ids(r.insertAfter("s1", probe()).visibleSteps))
    }

    @Test fun replacesAStaleProbeWhenTheAnchorAnswerChanges() {
        val r = runner(nps("s1"), nps("s2"))
        r.record(score("s1", 3))
        r.insertAfter("s1", probe("probe:s1:1"))
        r.submitPage()
        r.record(probeAnswer("probe:s1:1"))
        r.back()
        r.record(score("s1", 8))
        r.removeInsertedAfter("s1")
        r.insertAfter("s1", probe("probe:s1:2"))
        assertEquals(listOf("s1", "probe:s1:2", "s2"), ids(r.state().visibleSteps))
        assertEquals(listOf("s1"), r.finish().answers.map { it.stepId })
    }

    // ---- setContent ----
    private val sc = FeedbackContent(
        listOf(
            nps("s1", "One"), nps("s2", "Two"),
            nps("s3", "Three", ""","showIf":{"stepId":"s2","op":"lte","value":6}"""), nps("s4", "Four"),
        ),
        Brand("#2F9461", null),
    )
    private fun bn(vararg t: Pair<String, String>) = FeedbackContent(t.map { (id, title) -> nps(id, title) }, Brand("#2F9461", null))
    private fun titles(r: FeedbackRunner) = r.state().pageSteps.map { it.title }

    @Test fun setContentKeepsPageAnswersProbesAndConditionalStepsAndSwapsTheText() {
        val r = FeedbackRunner(sc, null)
        r.answer(score("s1", 9))
        r.record(score("s2", 3))
        r.insertAfter("s2", probe("probe:s2:1"))
        assertEquals(listOf("Two"), titles(r))

        val s = r.setContent(bn("s1" to "bOne", "s2" to "bTwo", "s3" to "bThree", "s4" to "bFour"))
        assertEquals(1, s.pageIndex)
        assertEquals(listOf("bTwo"), titles(r))
        assertEquals(listOf("s1", "s2"), s.answers.map { it.stepId })
        assertEquals(listOf("s1", "s2", "probe:s2:1", "s3", "s4"), ids(s.visibleSteps))

        r.submitPage()
        assertEquals(listOf("Why?"), titles(r)) // the probe keeps its own text
        r.answer(probeAnswer("probe:s2:1"))
        assertEquals(listOf("bThree"), titles(r))
        assertEquals(listOf("s1", "s2", "probe:s2:1"), r.finish().answers.map { it.stepId })
    }

    @Test fun setContentReadsThanksFromTheNewContentAndKeepsThePhase() {
        val r = FeedbackRunner(sc, null)
        r.setContent(bn("s1" to "x"))
        assertTrue(r.thanksEnabled())
        r.answer(score("s1", 9)); r.answer(score("s2", 9)); r.answer(score("s4", 9))
        assertEquals(Phase.THANKS, r.state().phase)
        assertEquals(Phase.THANKS, r.setContent(sc).phase)
    }

    @Test fun setContentKeepsAStepTheNewContentDoesNotHave() {
        val r = FeedbackRunner(sc, null)
        r.setContent(bn("s1" to "Uno"))
        assertEquals(listOf("Uno", "Two", "Four"), r.state().visibleSteps.map { it.title }) // s3 hidden: s2 unanswered
    }

    @Test fun setContentKeepsTheOldStepWhenTheNewOneHasADifferentKind() {
        val r = FeedbackRunner(sc, null)
        val star = parseStep(JSONObject("""{"id":"s1","kind":"star","title":"Stars"}"""))!!
        r.setContent(FeedbackContent(listOf(star), Brand("#2F9461", null)))
        assertEquals("One", r.state().visibleSteps[0].title)
        assertEquals("nps", r.state().visibleSteps[0].kind)
    }
}
