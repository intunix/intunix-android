package com.intunix.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from @intunix/core experiences/cadence.test.ts. */
class CadenceTest {
    private val day = DAY_MS
    private val now = 1_700_000_000_000L
    private val sid = "sess-1"
    private val zero = Wait.Days(0.0)

    private fun shown(lastShownAt: Long = now, count: Int = 1) = ShownRecord(lastShownAt, count, sid)
    private fun cad(answered: Wait = zero, dismissed: Wait = zero, ignored: Wait = zero, mode: String = "cooldown", giveUp: Int? = null) =
        NormalizedCadence(mode, answered, dismissed, ignored, giveUp)

    @Test fun defaultsToEverySession() {
        assertEquals(NormalizedCadence("every_session", Wait.Session, Wait.Session, Wait.Session), normalizeCadence(null))
    }

    @Test fun onceIsNeverEverywhere() {
        assertEquals(NormalizedCadence("once", Wait.Never, Wait.Never, Wait.Never), normalizeCadence(Cadence("once", null, null, null, null)))
    }

    @Test fun alwaysDropsGiveUpCap() {
        assertEquals(NormalizedCadence("always", zero, zero, zero), normalizeCadence(Cadence("always", null, null, null, 3)))
    }

    @Test fun absentCooldownWaitsAreZero() {
        assertEquals(
            NormalizedCadence("cooldown", Wait.Days(30.0), zero, zero),
            normalizeCadence(Cadence("cooldown", Wait.Days(30.0), null, null, null)),
        )
    }

    @Test fun neverShownAlwaysAllowed() {
        assertTrue(checkCadence(cad(Wait.Never, Wait.Never, Wait.Never), null, null, now, sid))
    }

    @Test fun neverWaitBlocksForever() {
        val o = OutcomeRecord(now - 999 * day, 1, "responded", now - 999 * day)
        assertFalse(checkCadence(cad(answered = Wait.Never), shown(now - 999 * day), o, now, sid))
    }

    @Test fun sessionWaitClearsInNewSession() {
        val c = cad(ignored = Wait.Session)
        assertFalse(checkCadence(c, shown(), null, now, sid))
        assertTrue(checkCadence(c, shown(), null, now, "sess-2"))
    }

    @Test fun dayWaitFollowsOutcome() {
        val c = cad(Wait.Days(30.0), Wait.Days(7.0), Wait.Days(1.0))
        fun answered(at: Long) = OutcomeRecord(at, 1, "responded", at)
        assertFalse(checkCadence(c, shown(now - 10 * day), answered(now - 10 * day), now, sid))
        assertTrue(checkCadence(c, shown(now - 31 * day), answered(now - 31 * day), now, sid))
        assertTrue(checkCadence(c, shown(now - 10 * day), OutcomeRecord(null, 1, "dismissed", now - 10 * day), now, sid))
    }

    @Test fun noOutcomeReadsAsIgnored() {
        assertFalse(checkCadence(cad(ignored = Wait.Never), shown(), null, now, sid))
    }

    @Test fun giveUpCapOnlyForNonResponders() {
        val c = cad(giveUp = 3)
        val ignored = OutcomeRecord(null, 3, "timeout", now)
        assertFalse(checkCadence(c, shown(count = 3), ignored, now, sid))
        assertTrue(checkCadence(c, shown(count = 2), ignored, now, sid))
        val answered = OutcomeRecord(now - 99 * day, 3, "responded", now - 99 * day)
        assertTrue(checkCadence(c, shown(now - 99 * day, 9), answered, now, sid))
    }

    @Test fun alwaysIgnoresEverything() {
        assertTrue(checkCadence(cad(mode = "always", giveUp = 1), shown(count = 50), OutcomeRecord(null, 50, "timeout", now), now, sid))
    }
}
