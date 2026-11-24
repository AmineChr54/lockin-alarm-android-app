package app.lockinalarm

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Everything the app keeps, in device-protected storage: readable after a reboot, before the first unlock.
 * Writes are committed right away, so a killed process always resumes from the last step.
 */
@SuppressLint("ApplySharedPref")
object Store {
    /** Bumped on every change so the UI redraws. */
    val changed = MutableStateFlow(0L)
    fun bump() { changed.value = System.nanoTime() }

    fun dp(ctx: Context): Context = if (ctx.isDeviceProtectedStorage) ctx else ctx.createDeviceProtectedStorageContext()
    private fun p(ctx: Context): SharedPreferences = dp(ctx).getSharedPreferences("lockin", Context.MODE_PRIVATE)
    private fun put(ctx: Context, f: SharedPreferences.Editor.() -> Unit) { p(ctx).edit().apply(f).commit(); bump() }
    private fun array(ctx: Context, key: String) = JSONArray(p(ctx).getString(key, "[]"))

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    // ---------- alarms ----------
    fun alarms(ctx: Context): List<AlarmDef> = array(ctx, "alarms").let { a -> (0 until a.length()).map { AlarmDef.of(a.getJSONObject(it)) } }
    fun alarm(ctx: Context, id: String): AlarmDef? = alarms(ctx).firstOrNull { it.id == id }
    fun setAlarms(ctx: Context, list: List<AlarmDef>) = put(ctx) { putString("alarms", JSONArray(list.map { it.json() }).toString()) }
    fun putAlarm(ctx: Context, a: AlarmDef) = alarms(ctx).let { l -> setAlarms(ctx, if (l.any { it.id == a.id }) l.map { if (it.id == a.id) a else it } else l + a) }

    fun soundFile(ctx: Context, id: String) = File(dp(ctx).filesDir, "sound_$id")
    fun photoFile(ctx: Context, id: String) = File(dp(ctx).filesDir, "photo_$id")

    // ---------- tags ----------
    fun tags(ctx: Context): List<TagRef> = array(ctx, "tags").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { TagRef(it.getString("uid"), it.getString("name")) } }
    fun setTags(ctx: Context, list: List<TagRef>) = put(ctx) { putString("tags", JSONArray(list.map { JSONObject().put("uid", it.uid).put("name", it.name) }).toString()) }
    fun tagName(ctx: Context, uid: String) = tags(ctx).firstOrNull { it.uid == uid }?.name ?: "a removed tag"

    // ---------- the running wake-up ----------
    fun session(ctx: Context): Session = p(ctx).getString("session", null)?.let { runCatching { Session.of(JSONObject(it)) }.getOrNull() } ?: Session()
    fun setSession(ctx: Context, s: Session) = put(ctx) { putString("session", s.json().toString()) }

    /** When each alarm was promised to ring. */
    fun armed(ctx: Context): Map<String, Long> = JSONObject(p(ctx).getString("armed", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
    fun setArmed(ctx: Context, m: Map<String, Long>) = put(ctx) { putString("armed", JSONObject(m).toString()) }

    // ---------- stats ----------
    fun records(ctx: Context): List<Record> = Stats.of(array(ctx, "records"))
    fun addRecord(ctx: Context, r: Record) = put(ctx) { putString("records", Stats.json((records(ctx) + r).takeLast(Stats.KEEP)).toString()) }

    // ---------- settings ----------
    fun lockHours(ctx: Context) = p(ctx).getInt("lockHours", 0)
    fun setLockHours(ctx: Context, h: Int) = put(ctx) { putInt("lockHours", h) }
    /** Hours before the next ring for the pre-flight check, 0 = off. */
    fun preflightHours(ctx: Context) = p(ctx).getInt("preflightHours", 8)
    fun setPreflightHours(ctx: Context, h: Int) = put(ctx) { putInt("preflightHours", h) }

    fun test(ctx: Context): Pair<String, Boolean>? = p(ctx).getString("testId", null)?.let { it to p(ctx).getBoolean("testSilent", false) }
    fun setTest(ctx: Context, id: String, silent: Boolean) = put(ctx) { putString("testId", id).putBoolean("testSilent", silent) }
}
