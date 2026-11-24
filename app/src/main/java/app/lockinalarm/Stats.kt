package app.lockinalarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Wake-up history. Pure; stored on the phone only. */
object Stats {
    const val KEEP = 400

    fun json(list: List<Record>): JSONArray = JSONArray(list.map {
        JSONObject().put("id", it.alarmId).put("at", it.at).put("secs", it.scanSecs).put("snoozes", it.snoozes).put("awakeMissed", it.awakeMissed)
            .put("exit", it.exit).put("early", it.early).put("missed", it.missed)
    })

    fun of(a: JSONArray): List<Record> = (0 until a.length()).map { a.getJSONObject(it) }.map {
        Record(it.getString("id"), it.getLong("at"), it.optLong("secs", -1), it.optInt("snoozes"), it.optInt("awakeMissed"), it.optBoolean("exit"), it.optBoolean("early"), it.optBoolean("missed"))
    }

    fun clean(r: Record) = r.snoozes == 0 && r.awakeMissed == 0 && !r.exit && !r.missed

    /** One entry per day that had an alarm, oldest first: was the whole day clean? */
    fun days(list: List<Record>, zone: ZoneId): List<Pair<LocalDate, Boolean>> =
        list.groupBy { Schedule.dateOf(it.at, zone) }.toSortedMap().map { (d, rs) -> d to rs.all(::clean) }

    /** Clean days in a row up to the latest one. Days without an alarm (off, skipped) don't count either way. */
    fun streak(days: List<Pair<LocalDate, Boolean>>) = days.takeLastWhile { it.second }.size

    fun best(days: List<Pair<LocalDate, Boolean>>): Int {
        var best = 0
        var run = 0
        for ((_, ok) in days) { run = if (ok) run + 1 else 0; best = maxOf(best, run) }
        return best
    }

    /** Most clean mornings in one Mon-Sun week. */
    fun bestWeek(days: List<Pair<LocalDate, Boolean>>): Int =
        days.filter { it.second }.groupBy { it.first.get(IsoFields.WEEK_BASED_YEAR) to it.first.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }.maxOfOrNull { it.value.size } ?: 0

    fun medianSecs(list: List<Record>): Long? = list.map { it.scanSecs }.filter { it >= 0 }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
}
