package com.intunix.sdk.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

internal const val PROBE_TIMEOUT_MS = 10_000L

internal data class ProbeUpdate(val drop: List<String>, val insert: List<Pair<String, Step>>)

/** Asks the server, after answers to `aiFollowUp` steps, for one follow-up question (a probe). */
internal class EnhancedProbes(private val handle: ConversationHandle, private var locale: String?) {
    private var turn = 0
    private var disabled = false
    private var finished = false // server said done: ask no more
    private val sent = mutableMapOf<String, AnswerValue>()
    private val seen = mutableSetOf<String>()
    /** Probe ids returned per asked step id. */
    private val origin = mutableMapOf<String, List<String>>()

    fun setLocale(lang: String) { locale = lang }

    suspend fun afterPage(steps: List<Step>, answers: List<Answer>): ProbeUpdate {
        val drop = mutableListOf<String>()
        val insert = mutableListOf<Pair<String, Step>>()
        for (step in steps) {
            if (!(step.aiFollowUp || step.id.startsWith("probe:"))) continue
            val ans = answers.firstOrNull { it.stepId == step.id }
            val key = ans?.value
            val prev = sent[step.id]
            if (prev != null && prev != key) {
                drop += step.id
                forget(step.id)
                sent.remove(step.id)
            }
            if (ans == null || sent[step.id] == key) continue
            sent[step.id] = ans.value
            if (disabled || finished) continue
            val reply = ask(ans)
            if (reply?.done == true) finished = true
            val input = reply?.input
            if (input != null && input.id.startsWith("probe:") && input.kind == "followup" && input.title.isNotEmpty() && seen.add(input.id)) {
                origin[step.id] = (origin[step.id] ?: emptyList()) + input.id
                insert += step.id to input
            }
        }
        return ProbeUpdate(drop, insert)
    }

    /** Forget probes issued for a step and, through them, their own chain. */
    private fun forget(stepId: String) {
        for (id in origin[stepId] ?: emptyList()) {
            seen.remove(id)
            sent.remove(id)
            forget(id)
        }
        origin.remove(stepId)
    }

    private fun parse(o: org.json.JSONObject) = parseTurnReply(o) ?: error("invalid reply")

    /** One request (plus one busy retry) inside a single PROBE_TIMEOUT_MS budget. Null on failure. */
    private suspend fun ask(ans: Answer): TurnReply? {
        val locale = locale?.takeIf { it.isNotEmpty() } // one locale per request, busy retry included
        val req = { TurnRequest(handle.conversationId, turn, "answer", ans, locale) }
        var busyTwice = false
        val reply = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                var r = parse(handle.sendDetached(req())!!)
                turn = r.nextTurn
                if (r.busy) {
                    delay(BUSY_RETRY_MS)
                    r = parse(handle.sendDetached(req())!!)
                    if (r.busy) { busyTwice = true; return@withTimeoutOrNull null }
                    turn = r.nextTurn
                }
                r
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (reply == null && !busyTwice) disabled = true
        return reply
    }
}
