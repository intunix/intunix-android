package com.intunix.sdk.internal

internal const val DAY_MS = 86_400_000L

internal data class ShownRecord(val lastShownAt: Long, val count: Int, val sessionId: String?)

internal data class OutcomeRecord(
    /** Set only when the user answered. */
    val respondedAt: Long? = null,
    val showCount: Int = 0,
    val lastOutcome: String? = null,
    val lastOutcomeAt: Long? = null,
)

internal data class NormalizedCadence(
    val mode: String,
    val answered: Wait,
    val dismissed: Wait,
    val ignored: Wait,
    val giveUpAfterShows: Int? = null,
)

/** Same expansion as @intunix/core: every mode becomes one three-outcome wait map. */
internal fun normalizeCadence(c: Cadence?): NormalizedCadence {
    val zero = Wait.Days(0.0)
    return when (val mode = c?.mode ?: "every_session") {
        "once" -> NormalizedCadence(mode, Wait.Never, Wait.Never, Wait.Never)
        "always" -> NormalizedCadence(mode, zero, zero, zero)
        "cooldown" -> NormalizedCadence(mode, c?.answered ?: zero, c?.dismissed ?: zero, c?.ignored ?: zero, c?.giveUpAfterShows)
        else -> NormalizedCadence("every_session", Wait.Session, Wait.Session, Wait.Session, c?.giveUpAfterShows)
    }
}

/** Whether a form may show again. Every wait measures from the last show; the outcome picks the wait. */
internal fun checkCadence(
    cadence: NormalizedCadence,
    shown: ShownRecord?,
    outcome: OutcomeRecord?,
    now: Long,
    sessionId: String,
): Boolean {
    if (shown == null) return true
    val giveUp = cadence.giveUpAfterShows
    if (cadence.mode != "always" && giveUp != null && outcome?.respondedAt == null && shown.count >= giveUp) return false
    val wait = when (outcome?.lastOutcome) {
        "responded" -> cadence.answered
        "dismissed" -> cadence.dismissed
        else -> cadence.ignored
    }
    return when (wait) {
        Wait.Never -> false
        Wait.Session -> shown.sessionId != sessionId
        is Wait.Days -> now - shown.lastShownAt >= wait.days * DAY_MS
    }
}
