package app.lockinalarm

import java.time.ZoneId

/** Commitment lock: shortly before it rings, an alarm can only get stricter. Pure. */
object Lock {
    /** Ringing earlier is fine, but not so early that you could get it over with before going to bed. */
    const val MAX_EARLIER_MS = 2 * 3600_000L

    fun locked(a: AlarmDef, now: Long, hours: Int, zone: ZoneId): Boolean {
        if (hours <= 0) return false
        val at = Schedule.next(a, now, zone)
        return at > 0 && at - now <= hours * 3600_000L
    }

    fun anyLocked(all: List<AlarmDef>, now: Long, hours: Int, zone: ZoneId) = all.any { locked(it, now, hours, zone) }

    /** Why changing [old] to [new] (null = delete) isn't allowed right now, or null if it is. */
    fun refuse(old: AlarmDef, new: AlarmDef?, now: Long, hours: Int, zone: ZoneId): String? {
        if (!locked(old, now, hours, zone)) return null
        if (new == null) return "delete"
        if (!new.on) return "turn off"
        val a = Schedule.next(old, now, zone)
        val b = Schedule.next(new, now, zone)
        return when {
            b == 0L || b > a -> "ring later"
            b < a - MAX_EARLIER_MS -> "more than 2 h earlier"
            new.tags != old.tags -> "change tags"
            new.route.ordinal < old.route.ordinal -> "easier route"
            new.snoozes.size > old.snoozes.size || new.snoozes.zip(old.snoozes).any { (n, o) -> n > o } -> "more snooze"
            old.snoozeScan && !new.snoozeScan -> "easier snooze"
            old.awakeMin > 0 && (new.awakeMin == 0 || new.awakeMin > old.awakeMin) -> "weaker awake check"
            old.awakeScan && !new.awakeScan -> "easier awake check"
            new.sound != old.sound -> "change sound"
            old.forceMax && !new.forceMax -> "quieter"
            old.vibrate && !new.vibrate -> "no vibration"
            new.buildUp.ordinal > old.buildUp.ordinal -> "slower build-up"
            else -> null
        }
    }
}
