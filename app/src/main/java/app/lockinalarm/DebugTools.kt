package app.lockinalarm

import android.app.Activity
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime

/** Emulator only: no NFC there, so tags are faked and rings set up over adb. */
object DebugTools {
    fun run(ctx: Activity, i: Intent) {
        i.getStringExtra("add_tag")?.let { Store.setTags(ctx, Store.tags(ctx) + TagRef(it, "Tag $it")) }
        if (i.hasExtra("ring_in")) {
            val t = LocalDateTime.now().plusMinutes(i.getIntExtra("ring_in", 1).toLong())
            val a = (Store.alarms(ctx).firstOrNull() ?: AlarmDef(Store.newId(), "Morning")).copy(on = true, tags = Store.tags(ctx).map { it.uid })
                .let { it.copy(dates = it.dates + (t.toLocalDate() to t.hour * 60 + t.minute), awakeMin = i.getIntExtra("awake", 1), sunriseMin = i.getIntExtra("sunrise", 0),
                    route = Route.valueOf(i.getStringExtra("route") ?: "ANY"), snoozeScan = i.getBooleanExtra("snooze_scan", false), awakeScan = i.getBooleanExtra("awake_scan", false)) }
            Log.i(TAG, "debug ring: ${Engine.save(ctx, a) ?: "ok"}")
        }
        i.getStringExtra("tag")?.let { Log.i(TAG, "debug tag: ${said(ctx, ctx.scanned(it))}") }
        if (i.hasExtra("lock")) Log.i(TAG, "debug lock: ${Engine.setLockHours(ctx, i.getIntExtra("lock", 0)) ?: "ok"}")
        if (i.getBooleanExtra("preflight", false)) Notify.preflight(ctx)
        if (i.getBooleanExtra("fake_stats", false)) {
            val day = 86_400_000L
            (30 downTo 1).filter { it % 7 > 1 }.forEach { d -> Store.addRecord(ctx, Record("x", System.currentTimeMillis() - d * day, (20L + (d * 37) % 400), if (d % 5 == 0) 1 else 0, if (d == 11) 1 else 0, exit = false, early = d == 4)) }
        }
    }
}
