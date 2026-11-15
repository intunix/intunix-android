package com.intunix.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

internal const val TURN_TIMEOUT_MS = 10_000L
internal const val BUSY_RETRY_MS = 700L
internal const val MAX_BUSY_RETRIES = 2

internal data class ConversationState(
    val messages: List<ChatMessage>,
    val input: Step?,
    val inputJson: JSONObject?,
    val answered: Int,
    val total: Int,
    val pending: Boolean,
    val done: Boolean,
    val endedByRespondent: Boolean,
    val local: Boolean,
    /** The current input may be skipped right now. */
    val canSkip: Boolean,
)

/**
 * Sends [req] on [scope] (not the caller's job), so a blocking request cannot hold back a timeout.
 * Throws when the transport fails; returns null when [stop] completes first.
 */
internal suspend fun ConversationHandle.sendDetached(req: TurnRequest, stop: CompletableDeferred<Unit>? = null): JSONObject? {
    val d = scope.async { runCatching { transport.send(req) } } // runCatching: a failure must not cancel `scope`
    try {
        if (stop == null) return d.await().getOrThrow()
        return select<Result<JSONObject>?> { d.onAwait { it }; stop.onAwait { null } }?.getOrThrow()
    } finally {
        d.cancel()
    }
}

private fun isProbe(id: String) = id.startsWith("probe:")
private fun probeStep(id: String) = Step(id, "followup", "", required = false, input = FollowUpInput("free"))

/**
 * Renderer-agnostic chat controller. Public suspend methods never throw.
 * Call `dispose()` when the view is torn down without a result and `end()` only when the
 * respondent ends or closes the conversation.
 */
internal class ConversationController(
    private var content: FeedbackContent,
    private var handle: ConversationHandle,
    private var locale: String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var state = ConversationState(emptyList(), null, null, 0, 0, pending = false, done = false, endedByRespondent = false, local = false, canSkip = false)
    private val listeners = LinkedHashSet<() -> Unit>()
    private var answers = listOf<Answer>()
    private val skipped = mutableSetOf<String>()
    private val seen = mutableMapOf<String, Step>() // steps the server sent as input
    private var nextTurn = 0
    private var started = false
    private var disposed = false
    private val waits = mutableSetOf<CompletableDeferred<Unit>>() // busy-retry waits: completed by dispose()
    private val calls = mutableSetOf<CompletableDeferred<Unit>>() // in-flight calls: completed by dispose() unless end() was called
    private var broken = false // a request failed: never contact the transport again
    private var inflight = CompletableDeferred<Unit>().also { it.complete(Unit) }
    private val startedAt = clock()

    fun state(): ConversationState = state

    /** Later requests carry `lang` and local mode reads `content`; shown messages stay as they were. */
    fun setLanguage(lang: String, content: FeedbackContent, config: ConversationConfig?) {
        locale = lang
        this.content = content
        // Local mode reads welcome/completion messages from here.
        if (config != null) handle = ConversationHandle(config, handle.transport, handle.conversationId, handle.scope)
        val s = state
        if (!s.local || s.input == null) return
        content.steps.firstOrNull { it.id == s.input.id }?.let { same -> set { copy(input = same, inputJson = null) } }
    }

    fun subscribe(cb: () -> Unit): () -> Unit {
        listeners.add(cb)
        return { listeners.remove(cb) }
    }

    /** Stop locally and tell the server nothing. Later calls are no-ops; `result()` still works. */
    fun dispose() {
        disposed = true
        listeners.clear()
        waits.toList().forEach { it.complete(Unit) }
        // After end(), the in-flight call stays bounded by its timeout so the deferred `end` still follows it.
        if (!state.endedByRespondent) calls.toList().forEach { it.complete(Unit) }
    }

    suspend fun start() {
        if (started || disposed) return
        started = true
        send("resume", null)
    }

    /** True when the answer was accepted, false when ignored (a blank answer becomes a skip). */
    suspend fun answer(a: Answer): Boolean {
        val input = state.input
        if (disposed || state.pending || state.done || input == null || a.stepId != input.id) return false
        if (!isAnswerValid(input, a)) return false
        if (isEmptyAnswer(a)) { skip(); return false }
        answers = answers.filter { it.stepId != a.stepId } + a
        set { copy(messages = messages + ChatMessage("respondent", answerLabel(input, a))) }
        if (state.local) advanceLocal() else send("answer", a)
        return true
    }

    suspend fun skip() {
        val input = state.input
        if (disposed || !state.canSkip || input == null) return
        skipped.add(input.id)
        if (state.local) advanceLocal() else send("skip", null)
    }

    /** Detached so a hung request cannot block teardown; the server records `end` only after the request in flight. */
    fun end() {
        if (disposed || state.done) return
        set { copy(endedByRespondent = true, done = true, pending = false, input = null, inputJson = null) }
        if (state.local) return
        val before = inflight
        handle.scope.launch {
            try {
                before.await()
                if (!state.local && !broken) call(request("end", nextTurn, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { /* the respondent has left: nothing to change */ }
        }
    }

    fun result(): FeedbackResponse {
        val valid = answers.filter { a -> stepFor(a.stepId)?.let { isAnswerValid(it, a) } == true }
        return FeedbackResponse(valid, state.done && !state.endedByRespondent, clock() - startedAt, handle.conversationId)
    }

    private fun stepFor(id: String): Step? =
        content.steps.firstOrNull { it.id == id } ?: seen[id] ?: if (isProbe(id)) probeStep(id) else null

    private fun request(action: String, turn: Int, answer: Answer?) =
        TurnRequest(handle.conversationId, turn, action, answer, locale?.takeIf { it.isNotEmpty() })

    /** Bounded transport call; null on failure, timeout or dispose. */
    private suspend fun call(req: TurnRequest): JSONObject? {
        val stop = CompletableDeferred<Unit>()
        calls.add(stop)
        return try {
            withTimeoutOrNull(TURN_TIMEOUT_MS) { handle.sendDetached(req, stop) } // not withTimeout: its exception is a CancellationException
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            calls.remove(stop)
        }
    }

    private suspend fun callReply(req: TurnRequest): TurnReply? = call(req)?.let { if (isValidTurnReply(it)) parseTurnReply(it) else null }

    private suspend fun send(action: String, answer: Answer?) {
        set { copy(pending = true) }
        val mine = CompletableDeferred<Unit>()
        inflight = mine
        try {
            run(action, answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(action)
        } finally {
            mine.complete(Unit)
        }
    }

    private suspend fun run(action: String, answer: Answer?) {
        var turn = nextTurn
        var retries = 0
        while (true) {
            val reply = callReply(request(action, turn, answer)) ?: return fail(action)
            if (!reply.busy) nextTurn = reply.nextTurn
            if (disposed || state.endedByRespondent) return
            if (!reply.busy) return applyReply(reply, action == "resume")
            if (retries >= MAX_BUSY_RETRIES) return fail(action)
            turn = reply.nextTurn
            sleep(BUSY_RETRY_MS)
            if (disposed || state.endedByRespondent) return
            retries++
        }
    }

    private fun fail(action: String) {
        broken = true
        if (disposed) return
        if (!state.endedByRespondent) goLocal(action)
    }

    private suspend fun sleep(ms: Long) {
        val w = CompletableDeferred<Unit>()
        waits.add(w)
        try { withTimeoutOrNull(ms) { w.await() } } finally { waits.remove(w) }
    }

    private fun applyReply(reply: TurnReply, isStart: Boolean) {
        val added = reply.messages.map { ChatMessage("nia", it) }
        reply.input?.let { seen[it.id] = it }
        if (isStart) seed(reply)
        val restored = if (isStart) reply.transcript?.takeIf { it.isNotEmpty() } else null
        set {
            copy(
                messages = restored ?: if (isStart) added else messages + added,
                input = reply.input, inputJson = reply.inputJson, answered = reply.answered, total = reply.total,
                done = reply.done, pending = false,
            )
        }
    }

    /** Answers and skips given before a reload, when the server sends them. */
    private fun seed(reply: TurnReply) {
        val list = reply.answers ?: return
        val byStep = linkedMapOf<String, Answer>() // last entry per step wins
        for (o in list) {
            val id = o.str("stepId") ?: continue
            val step = content.steps.firstOrNull { it.id == id } ?: if (isProbe(id)) probeStep(id) else null
            val a = parseAnswer(o)
            if (step != null && a != null && isAnswerValid(step, a)) { byStep.remove(id); byStep[id] = a } else byStep.remove(id)
        }
        answers = byStep.values.toList()
        reply.skipped?.let { skipped.addAll(it) }
    }

    private fun goLocal(action: String) {
        set { copy(local = true, pending = false) }
        val welcome = handle.config.welcomeMessage
        if (action == "resume" && state.messages.isEmpty() && !welcome.isNullOrEmpty()) pushNia(welcome)
        // The failed action consumed the current input, so move to the next predefined step.
        advanceLocal(true)
    }

    private fun advanceLocal(dedupe: Boolean = false) {
        val next = visibleSteps().firstOrNull { !answered(it.id) && it.id !in skipped }
        pushNia(next?.title ?: (handle.config.completionMessage ?: content.thanks?.title ?: Defaults.THANKS_TITLE), dedupe)
        val steps = visibleSteps()
        val n = steps.count { answered(it.id) || it.id in skipped }
        set { if (next != null) copy(input = next, inputJson = null, answered = n, total = steps.size) else copy(input = null, inputJson = null, done = true, answered = n, total = steps.size) }
    }

    /** Hiding a step can hide its dependants, so filter until stable. */
    private fun visibleSteps(): List<Step> {
        var visible = content.steps
        while (true) {
            val ids = visible.map { it.id }.toSet()
            val current = answers.filter { it.stepId in ids }
            val next = content.steps.filter { evaluateShowIf(it.showIf, current) }
            if (next.size == visible.size) return next
            visible = next
        }
    }

    private fun answered(id: String) = answers.any { it.stepId == id }

    /** `dedupe`: skip a message the server already showed as the last one. */
    private fun pushNia(text: String, dedupe: Boolean = false) {
        val last = state.messages.lastOrNull()
        if (dedupe && last?.role == "nia" && last.text == text) return
        set { copy(messages = messages + ChatMessage("nia", text)) }
    }

    private fun set(patch: ConversationState.() -> ConversationState) {
        if (disposed) return
        val s = state.patch()
        state = s.copy(canSkip = !s.pending && !s.done && s.input != null && isAnswerValid(s.input, null))
        for (cb in listeners.toList()) runCatching { cb() }
    }
}
