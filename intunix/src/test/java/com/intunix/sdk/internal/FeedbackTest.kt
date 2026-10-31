package com.intunix.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {
    private fun content(steps: String, extra: String = "") =
        parseFeedbackContent(JSONObject("""{"kind":"feedback","brand":{"color":"#2F9461"},"steps":[$steps]$extra}"""))!!

    private fun num(id: String, kind: String, v: Int) = Answer(id, kind, AnswerValue.Num(v))
    private fun text(id: String, type: String, t: String) = Answer(id, "followup", AnswerValue.Text(type, t))

    private val pageOne = """
        {"id":"a","kind":"nps","title":"Recommend?","page":1},
        {"id":"b","kind":"star","title":"Rate","page":1},
        {"id":"c","kind":"thumbs","title":"Helpful?"}"""

    @Test fun parsesStepsAndSkipsUnknownKinds() {
        val c = content("""$pageOne,{"id":"x","kind":"hologram","title":"?"},{"id":"s","kind":"smileys","title":"Mood","icons":"mood"}""")
        assertEquals(listOf("a", "b", "c", "s"), c.steps.map { it.id })
        assertEquals("mood", c.steps.last().icons)
    }

    @Test fun groupsConsecutivePages() {
        val c = content(pageOne)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), groupPages(c.steps).map { p -> p.map { it.id } })
    }

    @Test fun pageNeedsEveryAnswer() {
        val r = FeedbackRunner(content(pageOne), null)
        assertEquals(listOf("a", "b"), r.state().pageSteps.map { it.id })
        r.record(num("a", "nps", 9))
        assertFalse(isPageValid(r.state().pageSteps, r.state().answers))
        r.record(num("b", "star", 4))
        assertTrue(isPageValid(r.state().pageSteps, r.state().answers))
        r.submitPage()
        assertEquals(listOf("c"), r.state().pageSteps.map { it.id })
        assertEquals(1, r.state().pageIndex)
        assertTrue(r.state().paged)
    }

    @Test fun conditionalQuestionAppearsOnSamePage() {
        val r = FeedbackRunner(content("""
            {"id":"a","kind":"thumbs","title":"Helpful?","page":1},
            {"id":"why","kind":"followup","title":"Why not?","page":1,"showIf":{"stepId":"a","op":"eq","value":"down"},"input":{"type":"free"}}"""), null)
        assertEquals(listOf("a"), r.state().pageSteps.map { it.id })
        r.record(Answer("a", "thumbs", AnswerValue.Choice("down")))
        assertEquals(listOf("a", "why"), r.state().pageSteps.map { it.id })
        r.record(text("why", "free", "slow"))
        r.record(Answer("a", "thumbs", AnswerValue.Choice("up")))
        assertEquals(listOf("a"), r.state().pageSteps.map { it.id })
        assertEquals(1, r.state().answers.size)
    }

    @Test fun dismissDropsInvalidPartialAnswers() {
        val r = FeedbackRunner(content("""{"id":"e","kind":"followup","title":"Email","input":{"type":"email"}}"""), null)
        r.record(text("e", "email", "half@"))
        assertTrue(r.dismiss().answers.isEmpty())
    }

    @Test fun emptiedTextClearsAnswer() {
        val r = FeedbackRunner(content("""{"id":"f","kind":"followup","title":"More","input":{"type":"free"}}"""), null)
        r.record(text("f", "free", "hi"))
        r.record(text("f", "free", "  "))
        assertTrue(r.state().answers.isEmpty())
    }

    @Test fun thanksThenDone() {
        val r = FeedbackRunner(content("""{"id":"t","kind":"thumbs","title":"?"}"""), null)
        r.answer(Answer("t", "thumbs", AnswerValue.Choice("up")))
        assertEquals(Phase.THANKS, r.state().phase)
        val off = FeedbackRunner(content("""{"id":"t","kind":"thumbs","title":"?"}""", ""","thanks":{"enabled":false}"""), null)
        off.answer(Answer("t", "thumbs", AnswerValue.Choice("up")))
        assertEquals(Phase.DONE, off.state().phase)
    }

    @Test fun reviewPhaseWhenQualified() {
        val review = ReviewRequestConfig(true, "google", "https://g.page/r/x/review", "nps", 9.0, null, null, null, null, null, null)
        val r = FeedbackRunner(content("""{"id":"n","kind":"nps","title":"?"}"""), review)
        r.answer(num("n", "nps", 10))
        assertEquals(Phase.REVIEW, r.state().phase)
    }

    @Test fun validation() {
        val star = Step("s", "star", "R", max = 10)
        assertTrue(isAnswerValid(star, num("s", "star", 10)))
        assertFalse(isAnswerValid(star, num("s", "star", 11)))
        assertFalse(isAnswerValid(star, null))
        val optional = Step("f", "followup", "x", input = FollowUpInput("free", maxLength = 3))
        assertTrue(isAnswerValid(optional, null))
        assertFalse(isAnswerValid(optional, text("f", "free", "toolong")))
        val many = Step("m", "followup", "x", required = true, input = FollowUpInput("selectMultiple",
            options = listOf(ChoiceOption("a", "A", null), ChoiceOption("b", "B", null)), maxPick = 1))
        assertFalse(isAnswerValid(many, Answer("m", "followup", AnswerValue.Many(listOf("a", "b")))))
        assertTrue(isAnswerValid(many, Answer("m", "followup", AnswerValue.Many(listOf("a")))))
    }

    @Test fun showIfOps() {
        val a = listOf(num("n", "nps", 7))
        assertTrue(evaluateShowIf(ShowIf("n", "lte", 8, emptyList()), a))
        assertFalse(evaluateShowIf(ShowIf("n", "gt", 8, emptyList()), a))
        assertTrue(evaluateShowIf(ShowIf("n", "in", null, listOf(6, 7.0)), a))
        assertTrue(evaluateShowIf(ShowIf("n", "eq", 7.0, emptyList()), a))
        assertFalse(evaluateShowIf(ShowIf("x", "any", null, emptyList()), a))
    }

    @Test fun wireShapes() {
        assertEquals("""{"type":"selectMultiple","selected":["a"]}""", AnswerValue.Many(listOf("a")).toWire().toString())
        assertEquals(9, num("n", "nps", 9).value.toWire())
    }

    @Test fun reviewGuards() {
        assertNull(safeReviewUrl("javascript:alert(1)"))
        assertNull(safeReviewUrl("example.com"))
        assertNull(safeReviewUrl("https://x.com/" + "a".repeat(3000)))
        assertEquals("https://g.page/r/abc/review", safeReviewUrl(" https://g.page/r/abc/review "))
        val cfg = ReviewRequestConfig(true, "google", "https://g.page/r/x", "star", 4.0, null, null, null, null, null, null)
        assertTrue(reviewQualifies(cfg, listOf(num("s", "star", 4))))
        assertFalse(reviewQualifies(cfg, listOf(num("m", "smileys", 5))))
        assertFalse(reviewQualifies(cfg.copy(rule = "ai_sentiment"), listOf(num("s", "star", 5))))
        assertTrue(reviewQualifies(cfg.copy(rule = "always", threshold = null), emptyList()))
    }

    @Test fun reviewPresenterEvents() {
        val events = mutableListOf<String>()
        var done = 0
        val cfg = ReviewRequestConfig(true, "google", "https://g.page/r/x", "always", null, null, null, null, null, null, null)
        val p = ReviewPresenter.create(cfg, "f", "r", "#000", { events.add(it.name) }, { true }) { done++ }!!
        p.onClick(); p.onClick(); p.onSkip()
        assertEquals(listOf("\$review_request_clicked", "\$review_request_redirected"), events)
        assertEquals(1, done)
        assertNull(ReviewPresenter.create(cfg.copy(reviewUrl = "data:x"), "f", "r", null, {}, { true }) {})
    }
}
