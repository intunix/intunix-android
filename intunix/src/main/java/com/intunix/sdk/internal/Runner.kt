package com.intunix.sdk.internal

internal enum class Phase { RUNNING, THANKS, REVIEW, DONE }

internal data class RunnerState(
    val currentIndex: Int,
    val visibleSteps: List<Step>,
    val pageSteps: List<Step>,
    val pageIndex: Int,
    val pageCount: Int,
    val paged: Boolean,
    val answers: List<Answer>,
    val phase: Phase,
)

/** Consecutive steps sharing a `page` value form one page; a step without `page` is its own page. */
internal fun groupPages(steps: List<Step>): List<List<Step>> {
    val pages = mutableListOf<MutableList<Step>>()
    var prev: Step? = null
    for (s in steps) {
        if (prev != null && s.page != null && s.page == prev.page && pages.isNotEmpty()) pages.last().add(s)
        else pages.add(mutableListOf(s))
        prev = s
    }
    return pages
}

/** Port of @intunix/core FeedbackRunner. */
internal class FeedbackRunner(
    private var content: FeedbackContent,
    private val review: ReviewRequestConfig?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var answers = mutableListOf<Answer>()
    private var visible = listOf<Step>()
    private var pages = listOf<List<Step>>()
    private var page = 0
    private var phase = Phase.RUNNING
    private val startedAt = clock()
    private val paged = content.steps.any { it.page != null }
    /** Own copy so insertAfter never mutates the caller's content. */
    private var steps: MutableList<Step> = content.steps.toMutableList()
    /** Inserted step id -> id of the step it follows. */
    private val anchors = mutableMapOf<String, String>()

    init {
        recompute()
        if (visible.isEmpty()) advanceToEnd()
    }

    fun state(): RunnerState {
        val running = phase == Phase.RUNNING
        return RunnerState(
            currentIndex = if (running) firstIndexOf(page) else visible.size,
            visibleSteps = visible,
            pageSteps = if (running) pages.getOrNull(page) ?: emptyList() else emptyList(),
            pageIndex = if (running) page else pages.size,
            pageCount = pages.size,
            paged = paged,
            answers = answers.toList(),
            phase = phase,
        )
    }

    fun record(ans: Answer): RunnerState {
        if (phase != Phase.RUNNING || !onPage(ans.stepId)) return state()
        if (isEmptyAnswer(ans)) return clear(ans.stepId)
        val i = answers.indexOfFirst { it.stepId == ans.stepId }
        if (i >= 0) answers[i] = ans else answers.add(ans)
        recompute()
        return state()
    }

    fun clear(stepId: String): RunnerState {
        if (phase != Phase.RUNNING || !onPage(stepId)) return state()
        answers.removeAll { it.stepId == stepId }
        recompute()
        return state()
    }

    fun submitPage(): RunnerState {
        if (phase != Phase.RUNNING) return state()
        if (page >= pages.size - 1) advanceToEnd() else page += 1
        return state()
    }

    fun answer(ans: Answer): RunnerState {
        if (phase != Phase.RUNNING || !onPage(ans.stepId)) return state()
        record(ans)
        return submitPage()
    }

    fun prefill(ans: Answer): RunnerState = record(ans)
    fun next(): RunnerState = submitPage()

    fun back(): RunnerState {
        if (phase != Phase.RUNNING || page == 0) return state()
        page -= 1
        recompute()
        return state()
    }

    fun dismiss(): FeedbackResponse { phase = Phase.DONE; return FeedbackResponse(answersForVisible(), false, clock() - startedAt) }
    fun finish(): FeedbackResponse { phase = Phase.DONE; return FeedbackResponse(answersForVisible(), true, clock() - startedAt) }

    fun isLastPage(): Boolean = page == pages.size - 1

    /** Insert a step right after [afterStepId] (a page of its own). No-op if that id is unknown, [step] id exists, or the form is no longer running. */
    fun insertAfter(afterStepId: String, step: Step): RunnerState {
        val at = steps.indexOfFirst { it.id == afterStepId }
        if (phase != Phase.RUNNING || at < 0 || steps.any { it.id == step.id }) return state()
        // Keep a grouped page whole: insert after its last step, then after probes already inserted for this anchor.
        var end = at
        val group = steps[at].page
        while (group != null && steps.getOrNull(end + 1)?.page == group) end++
        val mine = insertedUnder(afterStepId)
        while (steps.getOrNull(end + 1)?.id in mine) end++
        keepPage {
            steps.add(end + 1, step)
            anchors[step.id] = afterStepId
        }
        return state()
    }

    /** Swap the text of the form (a language switch). Steps are replaced by id; probes, answers, page and phase stay. */
    fun setContent(content: FeedbackContent): RunnerState {
        val fresh = content.steps.associateBy { it.id }
        keepPage {
            this.content = content
            steps = steps.map { s -> fresh[s.id]?.takeIf { it.kind == s.kind } ?: s }.toMutableList() // a changed kind would orphan its answer
        }
        return state()
    }

    /** Remove every step inserted after [anchorStepId] (directly or chained), with their answers. */
    fun removeInsertedAfter(anchorStepId: String): RunnerState {
        val dead = insertedUnder(anchorStepId)
        if (dead.isEmpty()) return state()
        keepPage { removeSteps(dead) }
        return state()
    }

    private fun insertedUnder(anchor: String): Set<String> {
        val out = mutableSetOf<String>()
        do {
            val grew = anchors.entries.any { (id, a) -> id !in out && (a == anchor || a in out) && out.add(id) }
        } while (grew)
        return out
    }

    private fun removeSteps(dead: Set<String>) {
        steps.removeAll { it.id in dead }
        dead.forEach { anchors.remove(it) }
        answers.removeAll { it.stepId in dead }
    }

    /** Run a steps change, recompute, and stay on the step the respondent was on. */
    private fun keepPage(change: () -> Unit) {
        val here = if (phase == Phase.RUNNING) pages.getOrNull(page)?.firstOrNull()?.id else null
        change()
        recompute()
        val moved = if (here == null) -1 else pages.indexOfFirst { p -> p.any { it.id == here } }
        if (moved >= 0) page = moved
    }
    fun thanksEnabled(): Boolean = content.thanks?.enabled != false

    private fun onPage(stepId: String) = pages.getOrNull(page)?.any { it.id == stepId } == true
    private fun firstIndexOf(p: Int) = pages.take(p).sumOf { it.size }

    private fun advanceToEnd() {
        if (reviewQualifies(review, answersForVisible())) { phase = Phase.REVIEW; page = pages.size; return }
        if (thanksEnabled()) { phase = Phase.THANKS; page = pages.size } else phase = Phase.DONE
    }

    private fun recompute() {
        while (true) {
            val shown = mutableSetOf<String>()
            val orphans = mutableSetOf<String>()
            visible = steps.filter { s ->
                val anchor = anchors[s.id]
                // An inserted probe is removed for good with the step it followed.
                if (anchor != null && anchor !in shown) { orphans.add(s.id); return@filter false }
                evaluateShowIf(s.showIf, answers).also { if (it) shown.add(s.id) }
            }
            if (orphans.isNotEmpty()) removeSteps(orphans)
            val ids = visible.map { it.id }.toSet()
            val kept = answers.filter { it.stepId in ids }
            if (kept.size == answers.size) break
            answers = kept.toMutableList()
        }
        pages = groupPages(visible)
        if (phase == Phase.RUNNING && page >= pages.size) page = maxOf(0, pages.size - 1)
    }

    private fun answersForVisible(): List<Answer> = answers.filter { a ->
        val step = visible.firstOrNull { it.id == a.stepId }
        step != null && isAnswerValid(step, a)
    }
}

internal fun isEmptyAnswer(a: Answer): Boolean = when (val v = a.value) {
    is AnswerValue.Text -> v.text.isBlank()
    is AnswerValue.Many -> v.selected.isEmpty()
    is AnswerValue.One -> v.selected.isEmpty()
    else -> false
}
