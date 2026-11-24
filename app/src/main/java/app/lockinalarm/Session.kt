package app.lockinalarm

import org.json.JSONArray
import org.json.JSONObject

enum class Phase { IDLE, SUNRISE, RINGING, SNOOZED, AWAKE_WAIT, AWAKE_ASK }

/** One wake-up, from the first ring (or sunrise) until it is really over. Saved after every step. */
data class Session(
    val phase: Phase = Phase.IDLE,
    val alarmId: String? = null,
    val test: Boolean = false,
    val silent: Boolean = false,
    val startedAt: Long = 0,
    val ringSince: Long = 0,
    /** End of the current timed phase. */
    val until: Long = 0,
    val snoozesUsed: Int = 0,
    /** Scan-to-snooze: a scan before this moment snoozes instead of stopping. */
    val snoozeArmedUntil: Long = 0,
    /** Tags still to scan, in order (ORDER/RANDOM). */
    val need: List<String> = emptyList(),
    val awakeRound: Int = 0,
    /** Seconds from the first ring to the first complete scan, -1 = not yet. */
    val scanSecs: Long = -1,
    val early: Boolean = false,
    val exitUsed: Boolean = false,
    val queue: List<String> = emptyList(),
) {
    val active get() = phase != Phase.IDLE
    fun involves(id: String) = active && (alarmId == id || id in queue)

    fun json(): JSONObject = JSONObject().put("phase", phase.name).put("alarmId", alarmId ?: JSONObject.NULL).put("test", test).put("silent", silent)
        .put("startedAt", startedAt).put("ringSince", ringSince).put("until", until).put("snoozesUsed", snoozesUsed)
        .put("snoozeArmedUntil", snoozeArmedUntil).put("need", JSONArray(need)).put("awakeRound", awakeRound)
        .put("scanSecs", scanSecs).put("early", early).put("exitUsed", exitUsed).put("queue", JSONArray(queue))

    companion object {
        private fun list(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()
        fun of(o: JSONObject) = Session(
            Phase.valueOf(o.getString("phase")), if (o.isNull("alarmId")) null else o.getString("alarmId"), o.optBoolean("test"), o.optBoolean("silent"),
            o.optLong("startedAt"), o.optLong("ringSince"), o.optLong("until"), o.optInt("snoozesUsed"), o.optLong("snoozeArmedUntil"),
            list(o.optJSONArray("need")), o.optInt("awakeRound"), o.optLong("scanSecs", -1), o.optBoolean("early"), o.optBoolean("exitUsed"), list(o.optJSONArray("queue")),
        )
    }
}

sealed interface Event {
    data class Due(val id: String) : Event
    data class Test(val id: String, val silent: Boolean) : Event
    /** Sunrise of [id] begins, it rings at [ringAt]. */
    data class Sunrise(val id: String, val ringAt: Long) : Event
    data class Tag(val uid: String) : Event
    data object Snooze : Event
    /** "I'm up" on the awake check. */
    data object Awake : Event
    data object Tick : Event
    data object ExitTyped : Event
    data object EndTest : Event
}

/** What the person is told. */
enum class Said { NOTHING, NOT_RINGING, WRONG_TAG, WRONG_ORDER, NEXT_TAG, STOPPED, SNOOZED, SCAN_TO_SNOOZE, AWAKE, OVER }

/** One finished wake-up, for the stats. */
data class Record(val alarmId: String, val at: Long, val scanSecs: Long, val snoozes: Int, val awakeMissed: Int, val exit: Boolean, val early: Boolean, val missed: Boolean = false)

data class Step(
    val session: Session,
    val said: Said = Said.NOTHING,
    val record: Record? = null,
    /** Scanned during sunrise: this alarm's coming ring is done. */
    val skipRingOf: String? = null,
)

/** The state machine. Pure: (session, event, time) -> what happens. */
class Wake(private val def: (String) -> AlarmDef?, private val pick: (List<String>) -> String = { it.random() }) {
    companion object {
        const val ASK_MS = 60_000L
        const val SCAN_SNOOZE_MS = 10_000L
        const val MAX_ROUNDS = 3
        const val EXIT_AFTER_MS = 60_000L
        private const val SUNRISE_GRACE_MS = 120_000L
    }

    fun step(s: Session, e: Event, now: Long): Step {
        // an alarm that no longer exists can't hold the phone hostage
        if (s.active && s.alarmId?.let(def) == null) {
            val next = next(s.queue, now)
            return if (e is Event.Tick) Step(next) else step(next, e, now)
        }
        return when (e) {
            is Event.Due -> due(s, e.id, now)
            is Event.Test -> if (s.active || def(e.id) == null) Step(s) else Step(ring(e.id, now).copy(test = true, silent = e.silent))
            is Event.Sunrise -> if (s.active || def(e.id) == null || now >= e.ringAt) Step(s)
                else Step(Session(Phase.SUNRISE, e.id, startedAt = now, until = e.ringAt, need = route(e.id)))
            is Event.Tag -> tag(s, e.uid, now)
            Event.Snooze -> {
                val a = s.alarmId?.let(def)
                if (a == null || snoozeLeft(s, a) == 0) Step(s)
                else if (a.snoozeScan && !s.test) Step(s.copy(snoozeArmedUntil = now + SCAN_SNOOZE_MS), Said.SCAN_TO_SNOOZE)
                else snooze(s, a, now)
            }
            Event.Awake -> if (s.phase == Phase.AWAKE_ASK && def(s.alarmId!!)?.awakeScan == false) finish(s, now, Said.AWAKE) else Step(s)
            Event.Tick -> tick(s, now)
            Event.ExitTyped -> if (s.phase == Phase.RINGING && exitShown(s, now)) finish(s.copy(exitUsed = true), now, Said.OVER) else Step(s)
            Event.EndTest -> if (s.test) finish(s, now, Said.OVER) else Step(s)
        }
    }

    fun snoozeLeft(s: Session, a: AlarmDef) =
        if (s.phase != Phase.RINGING || s.awakeRound > 0) 0 else (a.snoozes.size - s.snoozesUsed).coerceAtLeast(0)

    fun exitShown(s: Session, now: Long) = now - s.ringSince >= EXIT_AFTER_MS

    private fun route(id: String): List<String> = def(id)?.let { a ->
        when {
            a.tags.isEmpty() || a.route == Route.ANY -> emptyList()
            a.route == Route.RANDOM -> listOf(pick(a.tags))
            else -> a.tags
        }
    } ?: emptyList()

    private fun ring(id: String, now: Long) = Session(Phase.RINGING, id, startedAt = now, ringSince = now, need = route(id))

    private fun next(queue: List<String>, now: Long): Session {
        val ok = queue.filter { def(it) != null }
        return if (ok.isEmpty()) Session() else ring(ok.first(), now).copy(queue = ok.drop(1))
    }

    private fun due(s: Session, id: String, now: Long): Step {
        if (def(id) == null) return Step(s)
        return when (s.phase) {
            Phase.IDLE -> Step(ring(id, now))
            // the sunrise's own ring keeps its route; another alarm's ring just takes over
            Phase.SUNRISE -> Step(if (s.alarmId == id) ring(id, now).copy(need = s.need) else ring(id, now))
            Phase.AWAKE_WAIT, Phase.AWAKE_ASK -> finish(s, now, Said.NOTHING).copy(session = ring(id, now).copy(queue = s.queue - id))
            else -> when {
                s.test -> Step(ring(id, now)) // the real thing beats a test
                s.alarmId == id || id in s.queue -> Step(s)
                else -> Step(s.copy(queue = s.queue + id))
            }
        }
    }

    private fun tag(s: Session, uid: String, now: Long): Step {
        val a = s.alarmId?.let(def) ?: return Step(s, Said.NOT_RINGING)
        when (s.phase) {
            Phase.IDLE, Phase.AWAKE_WAIT -> return Step(s, Said.NOT_RINGING)
            Phase.AWAKE_ASK -> return if (uid in a.tags) finish(s, now, Said.AWAKE) else Step(s, Said.WRONG_TAG)
            else -> {}
        }
        if (uid !in a.tags) return Step(s, Said.WRONG_TAG)
        if (s.phase == Phase.RINGING && now < s.snoozeArmedUntil && snoozeLeft(s, a) > 0) return snooze(s, a, now)
        if (s.need.isNotEmpty()) {
            if (uid != s.need.first()) return Step(s, Said.WRONG_ORDER)
            if (s.need.size > 1) return Step(s.copy(need = s.need.drop(1)), Said.NEXT_TAG)
        }
        if (s.phase == Phase.SUNRISE) return afterScan(s.copy(early = true, scanSecs = 0), a, now).copy(skipRingOf = a.id)
        return afterScan(if (s.scanSecs < 0) s.copy(scanSecs = (now - s.startedAt) / 1000) else s, a, now)
    }

    private fun afterScan(s: Session, a: AlarmDef, now: Long): Step =
        if (!s.test && a.awakeMin > 0 && s.awakeRound < MAX_ROUNDS)
            Step(s.copy(phase = Phase.AWAKE_WAIT, until = now + a.awakeMin * 60_000L, snoozeArmedUntil = 0), Said.STOPPED)
        else finish(s, now, Said.STOPPED)

    private fun snooze(s: Session, a: AlarmDef, now: Long): Step {
        val min = a.snoozes[s.snoozesUsed]
        return Step(s.copy(phase = Phase.SNOOZED, until = now + min * 60_000L, snoozesUsed = s.snoozesUsed + 1, snoozeArmedUntil = 0, need = route(a.id)), Said.SNOOZED)
    }

    private fun tick(s: Session, now: Long): Step = when {
        s.phase == Phase.SNOOZED && now >= s.until -> Step(s.copy(phase = Phase.RINGING, ringSince = now, until = 0))
        s.phase == Phase.AWAKE_WAIT && now >= s.until -> Step(s.copy(phase = Phase.AWAKE_ASK, until = now + ASK_MS))
        s.phase == Phase.AWAKE_ASK && now >= s.until ->
            Step(s.copy(phase = Phase.RINGING, ringSince = now, until = 0, awakeRound = s.awakeRound + 1, need = route(s.alarmId!!)))
        s.phase == Phase.SUNRISE && now >= s.until + SUNRISE_GRACE_MS -> Step(Session())
        else -> Step(s)
    }

    private fun finish(s: Session, now: Long, said: Said): Step {
        val rec = if (s.test) null else Record(s.alarmId!!, s.startedAt, s.scanSecs, s.snoozesUsed, s.awakeRound, s.exitUsed, s.early)
        return Step(next(s.queue, now), said, rec)
    }
}
