package app.lockinalarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTest {
    private val base = AlarmDef("a", on = true, tags = listOf("t1", "t2"), awakeMin = 0)
    private var defs = mapOf("a" to base)
    private fun with(d: AlarmDef) { defs = mapOf(d.id to d) }

    /** Every step goes through JSON, like a killed and restarted process. */
    private fun Session.on(e: Event, now: Long) = Wake({ defs[it] }, { it.last() }).step(Session.of(JSONObject(json().toString())), e, now)
    private fun ring() = Session().on(Event.Due("a"), 1000).session

    @Test fun tagMatching() {
        assertEquals(Said.WRONG_TAG, ring().on(Event.Tag("nope"), 2000).said)
        assertEquals(Said.WRONG_TAG, ring().on(Event.Tag(""), 2000).said)
        for (t in listOf("t1", "t2")) ring().on(Event.Tag(t), 61_000).let {
            assertEquals(Said.STOPPED, it.said)
            assertEquals(Phase.IDLE, it.session.phase)
            assertEquals(60L, it.record!!.scanSecs)
        }
        assertEquals(Said.NOT_RINGING, Session().on(Event.Tag("t1"), 1).said)
        with(base.copy(tags = emptyList()))
        assertEquals(Said.WRONG_TAG, ring().on(Event.Tag("t1"), 2000).said)
    }

    @Test fun earnedSnoozesShrinkAndRunOut() {
        var s = ring()
        var now = 1000L
        for (m in listOf(5, 3, 1)) {
            val st = s.on(Event.Snooze, now)
            assertEquals(Said.SNOOZED, st.said)
            assertEquals(now + m * 60_000L, st.session.until)
            assertEquals(Phase.SNOOZED, st.session.on(Event.Tick, st.session.until - 1).session.phase)
            now = st.session.until
            s = st.session.on(Event.Tick, now).session
            assertEquals(Phase.RINGING, s.phase)
        }
        assertEquals(s, s.on(Event.Snooze, now).session)
        assertEquals(Said.STOPPED, s.on(Event.Tag("t1"), now).said)
    }

    @Test fun scanToSnooze() {
        with(base.copy(snoozeScan = true))
        val armed = ring().on(Event.Snooze, 5000)
        assertEquals(Said.SCAN_TO_SNOOZE, armed.said)
        assertEquals(Phase.RINGING, armed.session.phase)
        assertEquals(Phase.SNOOZED, armed.session.on(Event.Tag("t1"), 14_000).session.phase)
        assertEquals(Said.STOPPED, armed.session.on(Event.Tag("t1"), 15_000).said) // too slow: a normal scan
        assertEquals(Said.WRONG_TAG, armed.session.on(Event.Tag("x"), 6000).said)
    }

    @Test fun scanWhileSnoozedStops() = assertEquals(Said.STOPPED, ring().on(Event.Snooze, 2000).session.on(Event.Tag("t1"), 3000).said)

    @Test fun routeInOrderAndResetBySnooze() {
        with(base.copy(route = Route.ORDER))
        assertEquals(Said.WRONG_ORDER, ring().on(Event.Tag("t2"), 2000).said)
        val half = ring().on(Event.Tag("t1"), 2000)
        assertEquals(Said.NEXT_TAG, half.said)
        assertEquals(Said.STOPPED, half.session.on(Event.Tag("t2"), 3000).said)
        val back = half.session.on(Event.Snooze, 3000).session.let { it.on(Event.Tick, it.until).session }
        assertEquals(listOf("t1", "t2"), back.need)
    }

    @Test fun randomTag() {
        with(base.copy(route = Route.RANDOM))
        assertEquals(Said.WRONG_ORDER, ring().on(Event.Tag("t1"), 2000).said)
        assertEquals(Said.STOPPED, ring().on(Event.Tag("t2"), 2000).said)
    }

    @Test fun awakeCheckAnswered() {
        with(base.copy(awakeMin = 7))
        val w = ring().on(Event.Tag("t1"), 2000)
        assertEquals(Phase.AWAKE_WAIT, w.session.phase)
        assertNull(w.record)
        assertEquals(Phase.AWAKE_WAIT, w.session.on(Event.Awake, 3000).session.phase) // not asked yet
        val ask = w.session.on(Event.Tick, 2000 + 7 * 60_000).session
        assertEquals(Phase.AWAKE_ASK, ask.phase)
        val done = ask.on(Event.Awake, ask.until - 1)
        assertEquals(Phase.IDLE, done.session.phase)
        assertTrue(Stats.clean(done.record!!))
    }

    @Test fun awakeCheckMissedRingsAgainWithoutSnoozeThreeRoundsMax() {
        with(base.copy(awakeMin = 1))
        var s = ring()
        var now = 2000L
        repeat(3) { round ->
            val scan = s.on(Event.Tag("t1"), now)
            assertEquals(Phase.AWAKE_WAIT, scan.session.phase)
            val ask = scan.session.on(Event.Tick, scan.session.until).session
            s = ask.on(Event.Tick, ask.until).session
            now = ask.until
            assertEquals(Phase.RINGING, s.phase)
            assertEquals(round + 1, s.awakeRound)
            assertEquals(s, s.on(Event.Snooze, now).session)
        }
        val end = s.on(Event.Tag("t1"), now)
        assertEquals(Phase.IDLE, end.session.phase)
        assertEquals(3, end.record!!.awakeMissed)
    }

    @Test fun awakeScanNeedsTheTag() {
        with(base.copy(awakeMin = 1, awakeScan = true))
        val w = ring().on(Event.Tag("t1"), 2000).session
        val ask = w.on(Event.Tick, w.until).session
        assertEquals(Phase.AWAKE_ASK, ask.on(Event.Awake, w.until).session.phase)
        assertEquals(Said.WRONG_TAG, ask.on(Event.Tag("x"), w.until).said)
        assertEquals(Said.AWAKE, ask.on(Event.Tag("t2"), w.until).said)
    }

    @Test fun overlapRingsRightAfter() {
        defs = mapOf("a" to base, "b" to AlarmDef("b", on = true, tags = listOf("t9"), awakeMin = 0))
        val both = ring().on(Event.Due("b"), 2000).session
        assertEquals(listOf("b"), both.on(Event.Due("b"), 2001).session.queue)
        val second = both.on(Event.Tag("t1"), 3000).session
        assertEquals(Phase.RINGING, second.phase)
        assertEquals("b", second.alarmId)
        assertEquals(Said.WRONG_TAG, second.on(Event.Tag("t1"), 4000).said)
        assertEquals(Phase.IDLE, second.on(Event.Tag("t9"), 4000).session.phase)
        // an open awake check gives way to the next alarm
        defs = defs + ("a" to base.copy(awakeMin = 5))
        val w = ring().on(Event.Tag("t1"), 2000).session.on(Event.Due("b"), 3000)
        assertEquals("b", w.session.alarmId)
        assertNotNull(w.record)
    }

    @Test fun sunriseScanIsEarlyAndSkipsTheRing() {
        val s = Session().on(Event.Sunrise("a", 600_000), 1000).session
        assertEquals(Phase.SUNRISE, s.phase)
        val st = s.on(Event.Tag("t1"), 5000)
        assertEquals("a", st.skipRingOf)
        assertTrue(st.record!!.early && Stats.clean(st.record!!))
        assertEquals(Phase.RINGING, s.on(Event.Due("a"), 600_000).session.phase)
        assertEquals(Phase.IDLE, s.on(Event.Tick, 600_000 + 120_000).session.phase)
        assertEquals(Phase.SUNRISE, s.on(Event.Snooze, 5000).session.phase)
        assertEquals(Phase.IDLE, Session().on(Event.Sunrise("a", 500), 1000).session.phase)
    }

    @Test fun lostTagExitOnlyAfterAMinute() {
        assertEquals(Phase.RINGING, ring().on(Event.ExitTyped, 60_000).session.phase)
        val e = ring().on(Event.ExitTyped, 61_000)
        assertEquals(Phase.IDLE, e.session.phase)
        assertTrue(e.record!!.exit)
        assertFalse(Stats.clean(e.record!!))
        with(base.copy(awakeMin = 7))
        assertEquals(Phase.IDLE, ring().on(Event.ExitTyped, 61_000).session.phase)
    }

    @Test fun testRing() {
        val t = Session().on(Event.Test("a", true), 1000).session
        assertTrue(t.test && t.silent)
        t.on(Event.Tag("t1"), 2000).let {
            assertNull(it.record)
            assertEquals(Phase.IDLE, it.session.phase)
        }
        assertEquals(Phase.IDLE, t.on(Event.EndTest, 2000).session.phase)
        assertEquals(Phase.RINGING, ring().on(Event.EndTest, 2000).session.phase)
        t.on(Event.Due("a"), 2000).session.let { assertFalse(it.test || it.silent) }
        assertFalse(ring().on(Event.Test("a", true), 2000).session.silent)
    }

    @Test fun deletedAlarmCantHang() {
        val s = ring()
        defs = emptyMap()
        assertEquals(Phase.IDLE, s.on(Event.Tick, 2000).session.phase)
        assertEquals(Said.NOT_RINGING, s.on(Event.Tag("t1"), 2000).said)
    }
}
