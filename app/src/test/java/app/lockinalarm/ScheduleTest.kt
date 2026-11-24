package app.lockinalarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val weekdays = AlarmDef("a", on = true, days = listOf(510, 435, 510, 510, 510, -1, -1))
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private fun t(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    // ---- weekly times ----
    @Test fun laterToday() = assertEquals(t(18, 8, 30), Schedule.nextLocal(weekdays, t(18, 6)))
    @Test fun tomorrowOwnTime() = assertEquals(t(17, 7, 15), Schedule.nextLocal(weekdays, t(16, 9)))
    @Test fun weekendSkipped() = assertEquals(t(23, 8, 30), Schedule.nextLocal(weekdays, t(20, 9)))
    @Test fun sameDayNextWeek() = assertEquals(t(25, 8), Schedule.nextLocal(AlarmDef("a", days = List(7) { if (it == 2) 480 else -1 }), t(18, 9)))
    @Test fun allOff() = assertNull(Schedule.nextLocal(AlarmDef("a"), t(18, 9)))

    // ---- skip / one-off ----
    private val wed = LocalDate.of(2026, 11, 18)
    @Test fun skipOnce() = assertEquals(t(19, 8, 30), Schedule.nextLocal(weekdays.copy(dates = mapOf(wed to -1)), t(18, 6)))
    @Test fun otherTimeOnce() = assertEquals(t(18, 6, 15), Schedule.nextLocal(weekdays.copy(dates = mapOf(wed to 375)), t(18, 5)))
    @Test fun laterTimeOnceReplacesTheWeekly() = assertEquals(t(18, 10), Schedule.nextLocal(weekdays.copy(dates = mapOf(wed to 600)), t(18, 6)))
    @Test fun oneOffFarAway() = assertEquals(LocalDateTime.of(2027, 1, 2, 5, 0), Schedule.nextLocal(AlarmDef("a", dates = mapOf(LocalDate.of(2027, 1, 2) to 300)), t(18, 6)))
    @Test fun oneOffEndsItself() {
        val a = AlarmDef("a", on = true, dates = mapOf(wed to 300))
        assertTrue(Schedule.tidy(a, wed).on)
        assertFalse(Schedule.tidy(a, wed.plusDays(1)).on)
    }
    @Test fun offMeansNone() = assertEquals(0L, Schedule.next(weekdays.copy(on = false), ms(t(18, 6)), zone))
    @Test fun skippingMoreThanAYear() = assertNull(Schedule.nextLocal(
        AlarmDef("a", days = listOf(510, -1, -1, -1, -1, -1, -1), dates = (0..60).associate { LocalDate.of(2026, 11, 16).plusWeeks(it.toLong()) to -1 }), t(16, 1)))

    // ---- DST ----
    @Test fun springForwardGap() {
        val a = AlarmDef("a", on = true, days = List(7) { 150 }) // 02:30 doesn't exist that night
        val at = Schedule.next(a, ms(LocalDateTime.of(2027, 3, 28, 1, 0)), zone)
        assertEquals(LocalDateTime.of(2027, 3, 28, 3, 30), LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone))
    }
    @Test fun autumnKeepsWallClock() {
        val at = Schedule.next(AlarmDef("a", on = true, days = List(7) { 510 }), ms(LocalDateTime.of(2026, 10, 24, 23, 0)), zone)
        assertEquals(LocalDateTime.of(2026, 10, 25, 8, 30), LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone))
    }

    // ---- due / missed ----
    @Test fun dueOverlapLateMissed() {
        val now = 10_000_000_000L
        val (ring, missed) = Schedule.due(mapOf("b" to now, "a" to now - 60_000, "late" to now - Schedule.LATE_MS, "gone" to now - Schedule.LATE_MS - 1, "future" to now + 60_000, "none" to 0L), now)
        assertEquals(listOf("late", "a", "b"), ring)
        assertEquals(listOf("gone"), missed)
    }

    // ---- json ----
    @Test fun jsonRoundTrip() {
        val a = weekdays.copy(dates = mapOf(LocalDate.of(2026, 12, 1) to -1), tags = listOf("04aa", "04bb"), route = Route.ORDER, sound = "file", soundName = "x.mp3", snoozes = listOf(5), awakeScan = true, sunriseMin = 10, buildUp = BuildUp.STAGES)
        assertEquals(a, AlarmDef.of(JSONObject(a.json().toString())))
        assertEquals(AlarmDef("z"), AlarmDef.of(JSONObject(AlarmDef("z").json().toString())))
    }
    @Test fun jsonClamps() {
        val a = AlarmDef.of(JSONObject("""{"id":"x","days":[9999,-5,0,1439,1440,5,5],"snoozes":[99,5,0,5,5,5],"awakeMin":999,"route":"NOPE","dates":{"rubbish":5},"new":1}"""))
        assertEquals(listOf(-1, -1, 0, 1439, -1, 5, 5), a.days)
        assertEquals(listOf(5, 5, 5), a.snoozes)
        assertEquals(30, a.awakeMin)
        assertEquals(Route.ANY, a.route)
        assertTrue(a.dates.isEmpty())
    }

    @Test fun rearmingSecondsBeforeARingKeepsIt() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val ring = java.time.ZonedDateTime.of(2026, 11, 25, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val a = AlarmDef("a", "A", on = true, days = List(7) { 420 })
        for (before in listOf(1L, 800L, 4_999L, 5_000L, 5_001L, 60_000L)) assertEquals("$before ms before", ring, Schedule.promise(a, ring, ring - before, zone))
        // a one-off too: losing it would also switch the alarm off
        val once = AlarmDef("b", "B", on = true, dates = mapOf(java.time.LocalDate.of(2026, 11, 25) to 420))
        assertEquals(ring, Schedule.promise(once, ring, ring - 2_000, zone))
        // once it rang, the promise is gone and tomorrow is next, even if the system alarm came a bit early
        assertEquals(ring + 86_400_000, Schedule.promise(a, 0, ring - 1_000, zone))
        // switched off: nothing is promised
        assertEquals(0L, Schedule.promise(a.copy(on = false), ring, ring - 2_000, zone))
    }

    @Test fun noEditsInTheLastSeconds() {
        assertTrue(Schedule.closing(10_000, 6_000))
        assertTrue(Schedule.closing(10_000, 12_000))
        assertFalse(Schedule.closing(10_000, 4_000))
        assertFalse(Schedule.closing(0, 4_000))
    }
}
