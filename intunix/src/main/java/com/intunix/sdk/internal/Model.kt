package com.intunix.sdk.internal

import org.json.JSONObject

internal sealed interface Trigger {
    data class Event(val name: String, val propertyEquals: Map<String, Any?>?) : Trigger
    /** App screens, matched against Intunix.screen(name). */
    data class Screen(val match: String, val value: String) : Trigger
    data class User(val traitEquals: Map<String, Any?>) : Trigger
    data class Time(val afterMs: Long) : Trigger
    data object Manual : Trigger
    /** url and exit_intent are web-only; unknown kinds from a newer console land here too. */
    data object Ignored : Trigger
}

internal sealed interface Wait {
    data class Days(val days: Double) : Wait
    data object Session : Wait
    data object Never : Wait
}

internal data class Cadence(
    val mode: String,
    val answered: Wait?,
    val dismissed: Wait?,
    val ignored: Wait?,
    val giveUpAfterShows: Int?,
)

internal data class TargetingRule(val kind: String, val key: String?, val op: String, val value: Any?, val hasValue: Boolean)
internal data class Targeting(val traitEquals: Map<String, Any?>, val rules: List<TargetingRule>)
internal data class Eligibility(
    val minDaysSinceFirstSeen: Double?,
    val minSessions: Int?,
    val startAt: String?,
    val endAt: String?,
)

internal data class ReviewRequestConfig(
    val enabled: Boolean,
    val platform: String,
    val reviewUrl: String?,
    val rule: String?,
    val threshold: Double?,
    val title: String?,
    val description: String?,
    val buttonText: String?,
    val skipText: String?,
    val themeColor: String?,
    val buttonStyle: String?,
)

internal data class Experience(
    val id: String,
    val slug: String?,
    val name: String?,
    val type: String,
    val platforms: List<String>?,
    val content: JSONObject,
    val triggers: List<Trigger>,
    val cadence: Cadence?,
    val targeting: Targeting?,
    val eligibility: Eligibility?,
    val frequencyScope: String?,
    val autoActivate: Boolean,
    val reviewRequest: ReviewRequestConfig?,
)

internal fun parseWait(v: Any?): Wait? = when (v) {
    is Number -> Wait.Days(v.toDouble())
    "session" -> Wait.Session
    "never" -> Wait.Never
    else -> null
}

internal fun parseTrigger(o: JSONObject): Trigger = when (o.str("kind")) {
    "event" -> o.str("eventName")?.let { Trigger.Event(it, o.obj("propertyEquals")?.toMap()) } ?: Trigger.Ignored
    "screen" -> {
        val value = o.str("value")
        if (value == null) Trigger.Ignored else Trigger.Screen(o.str("match") ?: "exact", value)
    }
    "user" -> Trigger.User(o.obj("traitEquals")?.toMap() ?: emptyMap())
    "time" -> o.long("afterMs")?.let { Trigger.Time(it) } ?: Trigger.Ignored
    "manual" -> Trigger.Manual
    else -> Trigger.Ignored
}

internal fun parseExperience(o: JSONObject): Experience? {
    val id = o.str("id") ?: return null
    val content = o.obj("content") ?: return null
    val cadence = o.obj("cadence")?.let { c ->
        val cd = c.obj("cooldown")
        Cadence(
            mode = c.str("mode") ?: "every_session",
            answered = cd?.let { parseWait(it.opt("answered")) },
            dismissed = cd?.let { parseWait(it.opt("dismissed")) },
            ignored = cd?.let { parseWait(it.opt("ignored")) },
            giveUpAfterShows = c.int("giveUpAfterShows"),
        )
    }
    val targeting = o.obj("targeting")?.let { t ->
        Targeting(
            traitEquals = t.obj("traitEquals")?.toMap() ?: emptyMap(),
            rules = t.arr("rules")?.objects()?.mapNotNull { r ->
                val kind = r.str("kind") ?: return@mapNotNull null
                val op = r.str("op") ?: return@mapNotNull null
                TargetingRule(kind, r.str("key"), op, fromJson(r.opt("value")), r.has("value"))
            } ?: emptyList(),
        )
    }
    val eligibility = o.obj("eligibility")?.let { e ->
        Eligibility(e.double("minDaysSinceFirstSeen"), e.int("minSessions"), e.str("startAt"), e.str("endAt"))
    }
    val review = o.obj("reviewRequest")?.let { r ->
        val trig = r.obj("trigger")
        ReviewRequestConfig(
            enabled = r.bool("enabled") == true,
            platform = r.str("platform") ?: "custom",
            reviewUrl = r.str("reviewUrl"),
            rule = trig?.str("rule"),
            threshold = trig?.double("threshold"),
            title = r.str("title"),
            description = r.str("description"),
            buttonText = r.str("buttonText"),
            skipText = r.str("skipText"),
            themeColor = r.str("themeColor"),
            buttonStyle = r.str("buttonStyle"),
        )
    }
    return Experience(
        id = id,
        slug = o.str("slug"),
        name = o.str("name"),
        type = o.str("type") ?: "modal",
        platforms = o.arr("platforms")?.strings(),
        content = content,
        triggers = o.arr("triggers")?.objects()?.map(::parseTrigger) ?: emptyList(),
        cadence = cadence,
        targeting = targeting,
        eligibility = eligibility,
        frequencyScope = o.str("frequencyScope"),
        autoActivate = o.bool("autoActivate") == true,
        reviewRequest = review,
    )
}
