package app.lockinalarm

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** When things ring. Pure: no Android in here. */
object Schedule {
    /** A ring this long ago (phone was off or rebooting) still rings late; older ones count as missed. */
    const val LATE_MS = 30 * 60_000L
    /** A ring this close counts as over for [next], so one that just rang isn't found again. */
    private const val CLOSE_S = 5L

    private fun at(date: LocalDate, m: Int) = date.atTime(LocalTime.of(m / 60, m % 60))

    /** Next ring of [a] after [now], whether or not it is switched on. */
    fun nextLocal(a: AlarmDef, now: LocalDateTime): LocalDateTime? {
        val from = now.plusSeconds(CLOSE_S)
        val single = a.dates.filterValues { it >= 0 }.map { (d, m) -> at(d, m) }.filter { it.isAfter(from) }.minOrNull()
        var weekly: LocalDateTime? = null
        if (a.days.any { it >= 0 }) for (i in 0..400) {
            val date = now.toLocalDate().plusDays(i.toLong())
            if (date in a.dates) continue
            val m = a.days[date.dayOfWeek.value - 1]
            if (m < 0) continue
            val t = at(date, m)
            if (t.isAfter(from)) { weekly = t; break }
        }
        return listOfNotNull(single, weekly).minOrNull()
    }

    /** Epoch millis of the next ring, 0 = none (off, or no times). */
    fun next(a: AlarmDef, nowMs: Long, zone: ZoneId): Long {
        if (!a.on) return 0
        return nextLocal(a, LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone))?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0
    }

    /** Like [next], but a ring already promised for the next seconds stays promised. */
    fun promise(a: AlarmDef, armed: Long, nowMs: Long, zone: ZoneId): Long =
        if (a.on && armed - nowMs in 1..CLOSE_S * 1000) armed else next(a, nowMs, zone)

    /** Too close to its ring to be changed any more. */
    fun closing(armed: Long, nowMs: Long) = armed in 1..nowMs + CLOSE_S * 1000

    fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Drops past dates. A one-off whose date is over switches itself off. */
    fun tidy(a: AlarmDef, today: LocalDate): AlarmDef {
        val keep = a.dates.filterKeys { !it.isBefore(today) }
        val b = if (keep.size == a.dates.size) a else a.copy(dates = keep)
        return if (b.on && !b.hasTimes) b.copy(on = false) else b
    }

    /** [armed] = when each alarm was promised to ring. Returns (ring now, too late), earliest first. */
    fun due(armed: Map<String, Long>, nowMs: Long): Pair<List<String>, List<String>> {
        val past = armed.entries.filter { it.value in 1..(nowMs + 1500) }.sortedBy { it.value }
        val (ring, missed) = past.partition { nowMs - it.value <= LATE_MS }
        return ring.map { it.key } to missed.map { it.key }
    }
}
