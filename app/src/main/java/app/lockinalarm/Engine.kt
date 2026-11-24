package app.lockinalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.ZoneId

const val TAG = "LockIn"

/** Glue between the pure rules (Schedule, Wake, Lock) and Android: AlarmManager, service, notifications. */
object Engine {
    const val RING = "app.lockinalarm.RING"
    const val SESSION = "app.lockinalarm.SESSION"
    const val SUNRISE = "app.lockinalarm.SUNRISE"
    const val PREFLIGHT = "app.lockinalarm.PREFLIGHT"
    const val TEST = "app.lockinalarm.TEST"
    private const val BACKUP_MS = 2 * 60_000L

    private fun now() = System.currentTimeMillis()
    private val zone: ZoneId get() = ZoneId.systemDefault()
    fun wake(ctx: Context) = Wake({ Store.alarm(ctx, it) })

    // ---------- the running wake-up ----------
    @Synchronized
    fun dispatch(ctx: Context, e: Event): Step {
        val before = Store.session(ctx)
        val st = wake(ctx).step(before, e, now())
        // the record goes first: if we die in between, the worst case is a double entry, never a lost alarm
        st.record?.let { Store.addRecord(ctx, it) }
        if (st.session != before) {
            Store.setSession(ctx, st.session)
            Log.i(TAG, "$e: ${before.phase} -> ${st.session.phase} (${st.said})")
        }
        st.skipRingOf?.let { id ->
            val at = Store.armed(ctx)[id] ?: 0
            Store.alarm(ctx, id)?.takeIf { at > now() }?.let {
                Store.putAlarm(ctx, it.copy(dates = it.dates + (Schedule.dateOf(at, zone) to -1)))
                Store.setArmed(ctx, Store.armed(ctx) - id) // this promise is kept: up already
            }
            rearm(ctx)
        }
        apply(ctx, before, st.session)
        return st
    }

    private fun apply(ctx: Context, before: Session, s: Session) {
        scheduleSession(ctx, s)
        if (s.phase == Phase.RINGING) {
            try { ctx.startForegroundService(Intent(ctx, AlarmService::class.java)) }
            catch (e: Exception) {
                // not allowed from here (boot): an alarm clock a moment later is
                Log.w(TAG, "service start blocked, retrying by alarm: $e")
                clock(ctx, SESSION, now() + 3000)
            }
        } else ctx.stopService(Intent(ctx, AlarmService::class.java))
        when (s.phase) {
            Phase.AWAKE_ASK, Phase.SUNRISE -> if (before.phase != s.phase) Notify.fullScreen(ctx, s)
            else -> Notify.cancelFullScreen(ctx)
        }
    }

    private fun scheduleSession(ctx: Context, s: Session) {
        when (s.phase) {
            Phase.IDLE -> cancel(ctx, SESSION)
            Phase.RINGING -> clock(ctx, SESSION, now() + BACKUP_MS) // re-ring if we get killed
            Phase.SNOOZED, Phase.AWAKE_WAIT, Phase.AWAKE_ASK -> clock(ctx, SESSION, maxOf(s.until, now() + 1000))
            Phase.SUNRISE -> exact(ctx, SESSION, maxOf(s.until + 125_000, now() + 1000))
        }
    }

    // ---------- scheduling ----------
    /** Recomputes every promised ring and sets the system alarms. Anything already due is rung first, never dropped. */
    @Synchronized
    fun rearm(ctx: Context) {
        val n = now()
        val old = Store.armed(ctx)
        if (old.values.any { it in 1..n }) { clock(ctx, RING, n + 1000); return }
        val before = Store.alarms(ctx)
        val alarms = before.map { Schedule.tidy(it, Schedule.dateOf(n, zone)) }
            // a switch that is on must mean it will ring: a one-off that has rung goes off
            .map { if (it.on && Schedule.promise(it, old[it.id] ?: 0, n, zone) == 0L) it.copy(on = false) else it }
        if (alarms != before) Store.setAlarms(ctx, alarms)
        val armed = alarms.associate { it.id to Schedule.promise(it, old[it.id] ?: 0, n, zone) }.filterValues { it > 0 }
        Store.setArmed(ctx, armed)
        val next = armed.values.minOrNull()
        if (next == null) cancel(ctx, RING) else clock(ctx, RING, next)
        val sun = alarms.mapNotNull { a -> armed[a.id]?.takeIf { a.sunriseMin > 0 }?.minus(a.sunriseMin * 60_000L) }.filter { it > n }.minOrNull()
        if (sun == null) cancel(ctx, SUNRISE) else exact(ctx, SUNRISE, sun)
        val pre = next?.minus(Store.preflightHours(ctx) * 3600_000L)?.takeIf { Store.preflightHours(ctx) > 0 && it > n }
        if (pre == null) cancel(ctx, PREFLIGHT) else exact(ctx, PREFLIGHT, pre)
        Log.i(TAG, "armed $armed")
    }

    @Synchronized
    fun processDue(ctx: Context) {
        val armed = Store.armed(ctx)
        val (ring, missed) = Schedule.due(armed, now())
        ring.forEach { dispatch(ctx, Event.Due(it)) }
        missed.forEach { Log.w(TAG, "missed $it"); Store.addRecord(ctx, Record(it, armed.getValue(it), -1, 0, 0, exit = false, early = false, missed = true)) }
        // only forget a promise once it is ringing
        Store.setArmed(ctx, armed - ring.toSet() - missed.toSet())
        rearm(ctx)
    }

    fun startSunrise(ctx: Context) {
        val n = now()
        val armed = Store.armed(ctx)
        Store.alarms(ctx).firstOrNull { a -> a.sunriseMin > 0 && (armed[a.id] ?: 0).let { it > n && it - a.sunriseMin * 60_000L <= n + 1500 } }
            ?.let { dispatch(ctx, Event.Sunrise(it.id, armed.getValue(it.id))) }
        rearm(ctx)
    }

    /** After a boot, update or clock change. Starting the sound from here isn't allowed, so it goes through an alarm. */
    fun resume(ctx: Context) {
        rearm(ctx)
        val s = Store.session(ctx)
        if (s.phase == Phase.RINGING) clock(ctx, SESSION, now() + 1000) else scheduleSession(ctx, s)
    }

    fun test(ctx: Context, id: String, seconds: Int, silent: Boolean) {
        Store.setTest(ctx, id, silent)
        clock(ctx, TEST, now() + seconds * 1000L)
    }

    // ---------- edits (all go through here, so the lock can't be bypassed) ----------
    /** Saves [a]. Returns why not, or null if it's saved. */
    @Synchronized
    fun save(ctx: Context, a: AlarmDef): String? {
        val old = Store.alarm(ctx, a.id)
        // turning off the last day turns the alarm off
        val new = if (a.on && !a.hasTimes) a.copy(on = false) else a
        refuse(ctx, old, new)?.let { return it }
        if (new.on && new.tags.isEmpty()) return "Choose a tag for it first"
        Store.putAlarm(ctx, new)
        // an edited alarm is promised anew
        Store.setArmed(ctx, Store.armed(ctx) - a.id)
        rearm(ctx)
        return null
    }

    @Synchronized
    fun delete(ctx: Context, id: String): String? {
        refuse(ctx, Store.alarm(ctx, id), null)?.let { return it }
        Store.setAlarms(ctx, Store.alarms(ctx).filter { it.id != id })
        Store.soundFile(ctx, id).delete()
        Store.photoFile(ctx, id).delete()
        Store.setArmed(ctx, Store.armed(ctx) - id)
        rearm(ctx)
        return null
    }

    private fun refuse(ctx: Context, old: AlarmDef?, new: AlarmDef?): String? {
        if (old == null) return null
        val s = Store.session(ctx)
        if (s.involves(old.id) && !s.test) return "Not while this alarm is running"
        if (Schedule.closing(Store.armed(ctx)[old.id] ?: 0, now())) return "It's about to ring"
        return Lock.refuse(old, new, now(), Store.lockHours(ctx), zone)?.let { "Locked: $it 🔒" }
    }

    /** The own sound file is being replaced: the lock has to know, the key stays "file". */
    fun soundLocked(ctx: Context, id: String): String? = Store.alarm(ctx, id)?.let { a ->
        if (a.sound != "file") null else refuse(ctx, a, a.copy(sound = "other"))
    }

    fun removeTag(ctx: Context, uid: String): String? {
        val users = Store.alarms(ctx).filter { uid in it.tags }
        users.firstOrNull { it.on }?.let { return "\"${it.name}\" needs it" }
        if (Store.session(ctx).active) return "Not while an alarm is running"
        users.forEach { Store.putAlarm(ctx, it.copy(tags = it.tags - uid)) }
        Store.setTags(ctx, Store.tags(ctx).filter { it.uid != uid })
        return null
    }

    fun setLockHours(ctx: Context, h: Int): String? {
        val cur = Store.lockHours(ctx)
        if (h < cur && (Store.session(ctx).active || Lock.anyLocked(Store.alarms(ctx), now(), cur, zone))) return "Locked until it rings 🔒"
        Store.setLockHours(ctx, h)
        return null
    }

    fun locked(ctx: Context, a: AlarmDef) = Lock.locked(a, now(), Store.lockHours(ctx), zone)
    fun anyLocked(ctx: Context) = Lock.anyLocked(Store.alarms(ctx), now(), Store.lockHours(ctx), zone)
    fun nextAt(a: AlarmDef) = Schedule.next(a, now(), zone)

    // ---------- AlarmManager ----------
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)
    private fun pi(ctx: Context, action: String) = PendingIntent.getBroadcast(
        ctx, listOf(RING, SESSION, SUNRISE, PREFLIGHT, TEST).indexOf(action) + 1,
        Intent(ctx, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private fun clock(ctx: Context, action: String, at: Long) {
        val show = PendingIntent.getActivity(ctx, 30, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(ctx, action))
    }
    private fun exact(ctx: Context, action: String, at: Long) = am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, action))
    private fun cancel(ctx: Context, action: String) = am(ctx).cancel(pi(ctx, action))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i(TAG, "receiver ${intent.action}")
        when (intent.action) {
            Engine.RING -> Engine.processDue(ctx)
            Engine.SESSION -> Engine.dispatch(ctx, Event.Tick)
            Engine.SUNRISE -> Engine.startSunrise(ctx)
            Engine.PREFLIGHT -> { Notify.preflight(ctx); Engine.rearm(ctx) }
            Engine.TEST -> Store.test(ctx)?.let { (id, silent) -> Engine.dispatch(ctx, Event.Test(id, silent)) }
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Engine.resume(ctx)
        }
    }
}
