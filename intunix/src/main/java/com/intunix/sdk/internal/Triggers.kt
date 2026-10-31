package com.intunix.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Event, screen, user-trait and time triggers. Forms fire only once registered (or autoActivate). */
internal class TriggerEvaluator(
    private val scope: CoroutineScope,
    private val screenDelayMs: Long,
    private val onMatch: (Experience) -> Unit,
) {
    private val forms = LinkedHashMap<String, Experience>()
    private val registered = mutableSetOf<String>()
    private val timeJobs = mutableMapOf<String, List<Job>>()
    private val screenJobs = mutableMapOf<String, Job>()
    var currentScreen: String = ""
        private set

    fun attach(experiences: List<Experience>) {
        experiences.forEach { forms[it.id] = it }
    }

    fun register(exp: Experience) {
        registered.add(exp.id)
        forms[exp.id] = exp
        scheduleTime(exp)
        checkScreenFor(exp)
    }

    fun unregister(id: String) {
        registered.remove(id)
        timeJobs.remove(id)?.forEach { it.cancel() }
        screenJobs.remove(id)?.cancel()
    }

    fun onEvent(name: String, properties: Map<String, Any?>) {
        for (exp in live()) for (t in exp.triggers) {
            if (t !is Trigger.Event || t.name != name) continue
            if (t.propertyEquals?.all { (k, v) -> jsEquals(properties[k], v) } == false) continue
            onMatch(exp)
        }
    }

    fun reevaluateUserTriggers(traits: Map<String, Any?>) {
        for (exp in live()) for (t in exp.triggers) {
            if (t is Trigger.User && t.traitEquals.all { (k, v) -> jsEquals(traits[k], v) }) onMatch(exp)
        }
    }

    fun onScreen(name: String) {
        currentScreen = name
        live().forEach(::checkScreenFor)
    }

    private fun live() = forms.values.filter { it.id in registered }

    private fun checkScreenFor(exp: Experience) {
        if (exp.id !in registered || currentScreen.isEmpty()) return
        if (exp.triggers.none { it is Trigger.Screen && screenMatches(it, currentScreen) }) return
        screenJobs.remove(exp.id)?.cancel()
        screenJobs[exp.id] = scope.launch {
            delay(screenDelayMs)
            screenJobs.remove(exp.id)
            if (exp.id in registered) onMatch(exp)
        }
    }

    private fun scheduleTime(exp: Experience) {
        timeJobs.remove(exp.id)?.forEach { it.cancel() }
        val jobs = exp.triggers.filterIsInstance<Trigger.Time>().map { t ->
            scope.launch {
                delay(t.afterMs)
                if (exp.id in registered) onMatch(exp)
            }
        }
        if (jobs.isNotEmpty()) timeJobs[exp.id] = jobs
    }
}

internal fun screenMatches(t: Trigger.Screen, screen: String): Boolean = when (t.match) {
    "contains" -> screen.contains(t.value)
    "regex" -> runCatching { Regex(t.value).containsMatchIn(screen) }.getOrDefault(false)
    else -> screen == t.value
}
