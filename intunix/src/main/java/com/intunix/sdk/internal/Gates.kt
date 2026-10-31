package com.intunix.sdk.internal

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class GateUser(val id: String?, val traits: Map<String, Any?>)

/** This SDK runs in apps; a form limited to the web never shows here. */
internal fun checkPlatform(exp: Experience): Boolean = exp.platforms?.contains("app") ?: true

private fun evalRule(rule: TargetingRule, user: GateUser?, context: Map<String, Any?>, screen: String): Boolean {
    val missing: Boolean
    val subject: Any?
    when (rule.kind) {
        // Web matches 'url' against the page URL; in apps the current screen plays that role.
        "url" -> { subject = screen; missing = false }
        "trait" -> { val m = user?.traits; missing = rule.key == null || m == null || !m.containsKey(rule.key); subject = m?.get(rule.key) }
        else -> { missing = rule.key == null || !context.containsKey(rule.key); subject = context[rule.key] }
    }
    if (missing && rule.op != "exists" && rule.op != "neq" && rule.op != "nin") return false
    return when (rule.op) {
        "eq" -> jsEquals(subject, rule.value)
        "neq" -> if (missing) rule.hasValue else !jsEquals(subject, rule.value)
        "in" -> (rule.value as? List<*>)?.any { jsEquals(it, subject) } ?: false
        "nin" -> (rule.value as? List<*>)?.let { list -> list.none { jsEquals(it, subject) } } ?: false
        "gt" -> subject is Number && rule.value is Number && subject.toDouble() > rule.value.toDouble()
        "lt" -> subject is Number && rule.value is Number && subject.toDouble() < rule.value.toDouble()
        "contains" -> jsString(subject).contains(jsString(rule.value))
        "exists" -> !missing && subject != null
        else -> false
    }
}

internal fun checkTargeting(exp: Experience, user: GateUser?, context: Map<String, Any?>, screen: String): Boolean {
    val t = exp.targeting ?: return true
    if (t.traitEquals.isNotEmpty()) {
        if (user == null) return false
        for ((k, v) in t.traitEquals) if (!jsEquals(user.traits[k], v)) return false
    }
    return t.rules.all { evalRule(it, user, context, screen) }
}

internal fun checkEligibility(exp: Experience, profile: ProfileData, now: Long): Boolean {
    val e = exp.eligibility ?: return true
    e.minDaysSinceFirstSeen?.let { if ((now - profile.firstSeenAt).toDouble() / DAY_MS < it) return false }
    e.minSessions?.let { if (profile.sessionCount < it) return false }
    e.startAt?.let { val at = parseIsoMillis(it) ?: return false; if (now < at) return false }
    e.endAt?.let { val at = parseIsoMillis(it) ?: return false; if (now > at) return false }
    return true
}

private val ISO_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
)

/** ISO-8601 to epoch ms, or null when malformed (a malformed window fails closed, as on web). */
internal fun parseIsoMillis(s: String): Long? {
    val text = s.trim()
    for (p in ISO_PATTERNS) {
        val f = SimpleDateFormat(p, Locale.US)
        f.isLenient = false
        // Date-only strings are UTC in JavaScript; date-times without an offset are local time.
        if (p == "yyyy-MM-dd") f.timeZone = TimeZone.getTimeZone("UTC")
        val pos = ParsePosition(0)
        val d = f.parse(text, pos)
        if (d != null && pos.index == text.length) return d.time
    }
    return null
}

/** The identity a shown/outcome record is scoped to (uid + the traits and context this form cares about). */
internal fun identityKey(exp: Experience, uid: String, traits: Map<String, Any?>, context: Map<String, Any?>): String {
    val traitKeys = sortedSetOf<String>()
    exp.targeting?.traitEquals?.keys?.let { traitKeys.addAll(it) }
    exp.targeting?.rules?.forEach { if (it.kind == "trait" && it.key != null) traitKeys.add(it.key) }
    exp.triggers.forEach { if (it is Trigger.User) traitKeys.addAll(it.traitEquals.keys) }

    val sb = StringBuilder(uid)
    if (traitKeys.isNotEmpty()) {
        sb.append('|').append(traitKeys.joinToString("|") { k -> "$k=${traits[k]?.let(::jsString) ?: ""}" })
    }
    if (exp.frequencyScope == "form_context") {
        val ruleKeys = exp.targeting?.rules?.filter { it.kind == "context" && it.key != null }?.map { it.key!! }?.toSet()
        val keys = (if (!ruleKeys.isNullOrEmpty()) ruleKeys else context.keys).sorted()
        val obj = keys.filter { context.containsKey(it) }.joinToString(",") { k ->
            "${org.json.JSONObject.quote(k)}:${jsonLiteral(context[k])}"
        }
        sb.append("|ctx={").append(obj).append('}')
    }
    return sb.toString()
}

private fun jsonLiteral(v: Any?): String = when (v) {
    null -> "null"
    is String -> org.json.JSONObject.quote(v)
    is Number -> jsString(v.toDouble())
    else -> v.toString()
}
