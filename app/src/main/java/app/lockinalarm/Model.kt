package app.lockinalarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** ANY = one of the tags is enough (spares), RANDOM = one picked each morning, ORDER = all of them in order. Easiest first. */
enum class Route(val label: String) { ANY("Any tag"), RANDOM("Random tag"), ORDER("All, in order") }

/** Loudest start first. */
enum class BuildUp(val label: String) {
    INSTANT("Instant"), FADE30("30 s"), FADE60("60 s"), STAGES("3 stages");

    /** Player volume [ms] after the ring started. */
    fun volume(ms: Long): Float = when (this) {
        INSTANT -> 1f
        FADE30 -> (ms / 30_000f).coerceIn(0.05f, 1f)
        FADE60 -> (ms / 60_000f).coerceIn(0.05f, 1f)
        STAGES -> if (ms < 40_000) 0.15f else if (ms < 80_000) 0.45f else 1f
    }
}

data class TagRef(val uid: String, val name: String)

/**
 * One alarm. [days] = minutes after midnight Mon..Sun, -1 = off. [dates] overrides single dates: -1 = skipped,
 * else the time on that date (a one-off alarm has only dates). Past dates are dropped by themselves.
 */
data class AlarmDef(
    val id: String,
    val name: String = "Alarm",
    val on: Boolean = false,
    val days: List<Int> = List(7) { -1 },
    val dates: Map<LocalDate, Int> = emptyMap(),
    val tags: List<String> = emptyList(),
    val route: Route = Route.ANY,
    val text: String = "LOCK IN",
    /** null = the phone's default alarm, "file" = own file, else a ringtone uri. */
    val sound: String? = null,
    val soundName: String? = null,
    val buildUp: BuildUp = BuildUp.INSTANT,
    val forceMax: Boolean = true,
    val vibrate: Boolean = true,
    /** Length of each snooze in minutes; empty = none. */
    val snoozes: List<Int> = listOf(5, 3, 1),
    val snoozeScan: Boolean = false,
    /** Minutes after the scan until "still up?", 0 = off. */
    val awakeMin: Int = 7,
    val awakeScan: Boolean = false,
    /** Minutes of sunrise before the ring, 0 = off. */
    val sunriseMin: Int = 0,
) {
    val hasTimes get() = days.any { it >= 0 } || dates.values.any { it >= 0 }

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("on", on)
        .put("days", JSONArray(days))
        .put("dates", JSONObject().also { o -> dates.forEach { (d, m) -> o.put(d.toString(), m) } })
        .put("tags", JSONArray(tags)).put("route", route.name).put("text", text)
        .put("sound", sound ?: JSONObject.NULL).put("soundName", soundName ?: JSONObject.NULL)
        .put("buildUp", buildUp.name).put("forceMax", forceMax).put("vibrate", vibrate)
        .put("snoozes", JSONArray(snoozes)).put("snoozeScan", snoozeScan)
        .put("awakeMin", awakeMin).put("awakeScan", awakeScan).put("sunriseMin", sunriseMin)

    companion object {
        const val MAX_SNOOZES = 3

        /** Reads what [json] wrote. Out-of-range values are clamped and unknown fields ignored, so imports are safe. */
        fun of(o: JSONObject): AlarmDef {
            val d = AlarmDef(o.getString("id"))
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
            fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }
            val minute = { m: Int -> if (m in 0..1439) m else -1 }
            return d.copy(
                name = (str("name") ?: d.name).take(24),
                on = o.optBoolean("on", false),
                days = ints("days")?.takeIf { it.size == 7 }?.map(minute) ?: d.days,
                dates = o.optJSONObject("dates")?.let { m ->
                    m.keys().asSequence().mapNotNull { k -> runCatching { LocalDate.parse(k) to minute(m.optInt(k, -1)) }.getOrNull() }.toMap()
                } ?: emptyMap(),
                tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.distinct() } ?: emptyList(),
                route = runCatching { Route.valueOf(o.optString("route")) }.getOrDefault(d.route),
                text = (str("text") ?: d.text).take(24),
                sound = str("sound"),
                soundName = str("soundName")?.take(60),
                buildUp = runCatching { BuildUp.valueOf(o.optString("buildUp")) }.getOrDefault(d.buildUp),
                forceMax = o.optBoolean("forceMax", d.forceMax),
                vibrate = o.optBoolean("vibrate", d.vibrate),
                snoozes = ints("snoozes")?.filter { it in 1..15 }?.take(MAX_SNOOZES) ?: d.snoozes,
                snoozeScan = o.optBoolean("snoozeScan", false),
                awakeMin = o.optInt("awakeMin", d.awakeMin).coerceIn(0, 30),
                awakeScan = o.optBoolean("awakeScan", false),
                sunriseMin = o.optInt("sunriseMin", 0).coerceIn(0, 30),
            )
        }
    }
}
