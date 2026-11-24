package app.lockinalarm.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lockinalarm.AlarmDef
import app.lockinalarm.BuildUp
import app.lockinalarm.Checks
import app.lockinalarm.Engine
import app.lockinalarm.Phase
import app.lockinalarm.Route
import app.lockinalarm.Schedule
import app.lockinalarm.Sounds
import app.lockinalarm.Store
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private fun local(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
private fun until(ms: Long): String {
    val m = ((ms - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return if (m < 60) "in $m min" else if (m < 48 * 60) "in ${m / 60} h ${m % 60} min" else "in ${m / (24 * 60)} days"
}

// ================= list =================
@Composable
fun AlarmsScreen(nav: Nav, host: Host) {
    val ctx = LocalContext.current
    tick()
    val alarms = Store.alarms(ctx)
    val next = alarms.map { it to Engine.nextAt(it) }.filter { it.second > 0 }.minByOrNull { it.second }
    val s = Store.session(ctx)
    Page {
        item { Title("Lock In *Alarm*") }
        item {
            Box(Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(26.dp)).background(C.pink)) {
                Column(Modifier.padding(18.dp)) {
                    val label = when (s.phase) {
                        Phase.SNOOZED -> "SNOOZED UNTIL"
                        Phase.AWAKE_WAIT, Phase.AWAKE_ASK -> "AWAKE CHECK AT"
                        else -> next?.let { "NEXT · " + local(it.second).format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) }?.uppercase() ?: "NO ALARM SET"
                    }
                    val at = if (s.phase == Phase.SNOOZED || s.phase == Phase.AWAKE_WAIT) s.until else next?.second
                    Text(label, style = T.label, color = C.ink.copy(alpha = 0.75f))
                    Text(at?.let { local(it).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "--:--", style = T.serif(66), color = C.ink)
                    Text(
                        if (next == null) "Add one below" else next.first.name + " · " + until(next.second) + if (Engine.locked(ctx, next.first)) " · locked" else "",
                        style = T.sans(13), color = C.ink,
                    )
                }
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 10.dp)) { Mascot(if (s.active) Mood.ACTIVE else Mood.IDLE, 104.dp) }
            }
        }
        val todo = Checks.setup(ctx).filter { !it.ok }
        if (todo.isNotEmpty() || Store.tags(ctx).isEmpty()) {
            item { SectionHeader("Before it *works*") }
            item {
                Group {
                    if (Store.tags(ctx).isEmpty()) ValueRow("Pair a tag", "pair", "the only thing that stops the alarm") { host.pair(true) }
                    todo.forEachIndexed { i, c ->
                        if (i > 0 || Store.tags(ctx).isEmpty()) Divider()
                        ValueRow(c.label, if (c.fix != null) "fix" else "", c.hint) { host.fix(c) }
                    }
                }
            }
        }
        item { SectionHeader("Alarms") }
        alarms.forEach { a -> item(key = a.id) { AlarmCard(a, host) { nav.go("alarm:${a.id}") } } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("New *alarm*", Modifier.weight(1f)) { create(ctx, nav) }
            }
        }
    }
}

private fun create(ctx: android.content.Context, nav: Nav) {
    val a = AlarmDef(Store.newId(), name = if (Store.alarms(ctx).isEmpty()) "Morning" else "Alarm ${Store.alarms(ctx).size + 1}", tags = Store.tags(ctx).take(1).map { it.uid })
    Engine.save(ctx, a)
    nav.go("alarm:${a.id}")
}

@Composable
private fun AlarmCard(a: AlarmDef, host: Host, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val at = Engine.nextAt(a)
    TCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.name, style = T.serif(26))
                    if (Engine.locked(ctx, a)) Icon(Ic.LOCK, C.coral, 16.dp)
                }
                Text(
                    if (at > 0) local(at).let { it.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)) + " · " + until(at) } else if (a.hasTimes) "off" else "no times yet",
                    style = T.sans(13), color = C.muted,
                )
                Text(
                    if (a.tags.isEmpty()) "no tag" else a.tags.joinToString(if (a.route == Route.ORDER) " → " else ", ") { Store.tagName(ctx, it) },
                    style = T.sans(13), color = if (a.tags.isEmpty()) C.bad else C.muted, maxLines = 1,
                )
            }
            Switch(a.on, { host.save(a.copy(on = it)) }, colors = SwitchDefaults.colors(
                checkedThumbColor = C.text, checkedTrackColor = C.green, uncheckedThumbColor = C.muted, uncheckedTrackColor = C.cardHi, uncheckedBorderColor = C.lineHi,
            ))
        }
    }
}

// ================= one alarm =================
@Composable
fun EditScreen(nav: Nav, host: Host, id: String) {
    val ctx = LocalContext.current
    tick()
    var dialog by remember { mutableStateOf<String?>(null) }
    val a = Store.alarm(ctx, id)
    if (a == null) { LaunchedEffect(Unit) { nav.back() }; return }
    val at = Engine.nextAt(a)
    val today = LocalDate.now()
    SubPage(a.name, nav, { CircleButton(Ic.EDIT, "Rename") { dialog = "name" } }) {
        if (Engine.locked(ctx, a)) item { Note("🔒 Locked until it rings: you can only make it stricter, or up to 2 h earlier.") }
        item { Group { ToggleRow("Alarm on", a.on, if (at > 0) "next: " + local(at).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)) else if (a.hasTimes) "off" else "set a time first") { on ->
            // a switched-on alarm without times would silently stay off
            if (on && !a.hasTimes) host.toast("Set a time first") else host.save(a.copy(on = on))
        } } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 0..6) {
                    val t = a.days[d]
                    val isNext = at > 0 && at - System.currentTimeMillis() < 7 * 86_400_000L && local(at).dayOfWeek.value - 1 == d
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (isNext) C.coral else if (t >= 0) C.cardHi else C.bg)
                            .border(1.dp, if (t >= 0 || isNext) C.line else C.lineHi, RoundedCornerShape(16.dp))
                            .pointerInput(a) {
                                fun set(m: Int) = host.save(a.copy(days = a.days.toMutableList().also { it[d] = m }, on = a.on || (m >= 0 && a.tags.isNotEmpty() && !a.hasTimes)))
                                detectTapGestures(
                                    onTap = { host.pickTime(if (t >= 0) t else a.days.firstOrNull { it >= 0 } ?: (8 * 60 + 30)) { m -> set(m) } },
                                    onLongPress = { set(-1) },
                                )
                            }.padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(DAY_NAMES[d], style = T.sans(12), color = if (isNext) C.ink else C.muted)
                        Text(if (isNext) hhmm(local(at).let { it.hour * 60 + it.minute }) else if (t >= 0) hhmm(t) else "off", style = T.sans(12, 600), color = if (isNext) C.ink else if (t >= 0) C.text else C.faint)
                    }
                }
            }
        }
        item { Note("Tap a day to set its time, hold it to turn that day off.") }
        item {
            Group {
                if (at > 0) {
                    ValueRow("Skip the next one", local(at).toLocalDate().format(DATE), "your week stays as it is") { host.save(a.copy(dates = a.dates + (local(at).toLocalDate() to -1))) }
                    Divider()
                    ValueRow("Other time just once", local(at).toLocalDate().format(DATE)) {
                        host.pickTime(local(at).let { it.hour * 60 + it.minute }) { m -> host.save(a.copy(dates = a.dates + (local(at).toLocalDate() to m))) }
                    }
                    Divider()
                }
                ValueRow("Add a single date", "+") { host.pickDate { d -> host.pickTime(7 * 60) { m -> host.save(a.copy(dates = a.dates + (d to m), on = a.on || (a.tags.isNotEmpty() && !a.hasTimes))) } } }
                a.dates.filterKeys { !it.isBefore(today) }.toSortedMap().forEach { (d, m) ->
                    Divider()
                    ValueRow(d.format(DATE), if (m < 0) "skipped" else hhmm(m), "tap to undo") { host.save(a.copy(dates = a.dates - d)) }
                }
            }
        }

        item { SectionHeader("What stops *it*") }
        item {
            Group {
                val tags = Store.tags(ctx)
                tags.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    val n = a.tags.indexOf(t.uid)
                    ToggleRow(t.name, n >= 0, if (n >= 0 && a.route == Route.ORDER) "stop ${n + 1}" else null) { on ->
                        host.save(a.copy(tags = if (on) a.tags + t.uid else a.tags - t.uid))
                    }
                }
                if (tags.isNotEmpty()) Divider()
                ValueRow("Pair a new tag", "+") { host.pair(true) }
            }
        }
        if (a.tags.size > 1) {
            item { Segmented(Route.entries.map { it.label }, a.route.ordinal) { host.save(a.copy(route = Route.entries[it])) } }
            item { Note(when (a.route) {
                Route.ANY -> "Any one of them stops it, the others are spares."
                Route.RANDOM -> "A different one each morning, shown on the alarm screen."
                Route.ORDER -> "All of them in this order. Switch one off and on to move it to the end."
            }) }
        }

        item { SectionHeader("Getting *up*") }
        item {
            Group {
                ValueRow("Snoozes", if (a.snoozes.isEmpty()) "none 😈" else a.snoozes.joinToString(" → ") + " min") { dialog = "snoozes" }
                if (a.snoozes.isNotEmpty()) { Divider(); ToggleRow("Scan to snooze", a.snoozeScan, "you have to reach a tag even for a snooze") { host.save(a.copy(snoozeScan = it)) } }
                Divider()
                ValueRow("Awake check", if (a.awakeMin == 0) "off" else "after ${a.awakeMin} min", "no answer within a minute: it rings again") { dialog = "awake" }
                if (a.awakeMin > 0) { Divider(); ToggleRow("Answer with the tag", a.awakeScan, "instead of a button") { host.save(a.copy(awakeScan = it)) } }
                Divider()
                ValueRow("Sunrise", if (a.sunriseMin == 0) "off" else "${a.sunriseMin} min before", "the screen brightens, lay the phone face up") { dialog = "sunrise" }
            }
        }

        item { SectionHeader("Sound", "Choose ›") { nav.go("sounds:$id") } }
        item {
            Group {
                SoundRow(a.soundName ?: "Default alarm", Sounds.uri(ctx, a) ?: Sounds.defaultUri(ctx), selected = true)
                Divider()
                Text("Build-up", style = T.sans(13), color = C.muted)
                Segmented(BuildUp.entries.map { it.label }, a.buildUp.ordinal) { host.save(a.copy(buildUp = BuildUp.entries[it])) }
                ToggleRow("Force max volume", a.forceMax, "keeps the alarm volume at max while it rings") { host.save(a.copy(forceMax = it)) }
                Divider()
                ToggleRow("Vibrate", a.vibrate) { host.save(a.copy(vibrate = it)) }
            }
        }

        item { SectionHeader("The *screen*") }
        item {
            Group {
                ValueRow("Big words", a.text) { dialog = "text" }
                Divider()
                ValueRow("Background photo", if (Store.photoFile(ctx, id).exists()) "set" else "none") { host.pickPhoto(a) }
                Divider()
                ValueRow("Test ring in 10 s", "silent", "lock the phone to see it like in the morning") {
                    if (Store.session(ctx).active) host.toast("An alarm is running") else { Engine.test(ctx, id, 10, true); host.toast("Rings silently in 10 s") }
                }
            }
        }
        item { BigButton("Delete", color = C.card, textColor = C.bad) { dialog = "delete" } }
    }
    when (dialog) {
        "name" -> InputDialog("*Name*", a.name, onDismiss = { dialog = null }) { host.save(a.copy(name = it.trim().take(24).ifEmpty { a.name })); dialog = null }
        "text" -> InputDialog("Big *words*", a.text, hint = "Shown on the alarm screen.", onDismiss = { dialog = null }) { host.save(a.copy(text = it.trim().take(24).ifEmpty { "LOCK IN" })); dialog = null }
        "snoozes" -> {
            val o = listOf(emptyList(), listOf(5), listOf(3, 1), listOf(5, 3, 1), listOf(5, 5, 5), listOf(10, 5, 3))
            ChoiceDialog("Snoozes", o.map { if (it.isEmpty()) "None 😈" else it.joinToString(" → ") + " min" }, o.indexOf(a.snoozes), { dialog = null }) { host.save(a.copy(snoozes = o[it])); dialog = null }
        }
        "awake" -> listOf(0, 3, 5, 7, 10, 15).let { o -> ChoiceDialog("Awake *check*", o.map { if (it == 0) "Off" else "after $it min" }, o.indexOf(a.awakeMin), { dialog = null }) { host.save(a.copy(awakeMin = o[it])); dialog = null } }
        "sunrise" -> listOf(0, 5, 10, 20).let { o -> ChoiceDialog("Sunrise", o.map { if (it == 0) "Off" else "$it min before" }, o.indexOf(a.sunriseMin), { dialog = null }) { host.save(a.copy(sunriseMin = o[it])); dialog = null } }
        "delete" -> ChoiceDialog("Delete *${a.name}*?", listOf("Yes, delete it"), -1, { dialog = null }) { dialog = null; val no = Engine.delete(ctx, id); if (no != null) host.toast(no) else nav.back() }
    }
}

// ================= sounds =================
/** Plays 5 s of a sound at media volume (never at alarm volume), one at a time. */
object Preview {
    private var player: MediaPlayer? = null
    val playing = mutableStateOf<String?>(null)
    fun toggle(ctx: android.content.Context, key: String, uri: Uri?) {
        val same = playing.value == key
        stop()
        if (same || uri == null) return
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(ctx, uri); prepare(); start()
            }
        } catch (e: Exception) { null }
        playing.value = if (player != null) key else null
    }
    fun stop() { player?.run { try { stop() } catch (_: Exception) {}; release() }; player = null; playing.value = null }
}

@Composable
private fun SoundRow(title: String, uri: Uri?, selected: Boolean, sub: String? = null, onPick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val key = uri?.toString() ?: title
    val on = Preview.playing.value == key
    LaunchedEffect(on) { if (on) { delay(5000); if (Preview.playing.value == key) Preview.stop() } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(if (on) Ic.STOP else Ic.PLAY, "Preview $title", bg = if (selected) C.coral else C.cardHi, tint = if (selected) C.ink else C.text, size = 38.dp) { Preview.toggle(ctx, key, uri) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).then(if (onPick != null) Modifier.clickable(role = Role.RadioButton, onClick = onPick) else Modifier)) {
            Text(title, style = T.sans(15))
            if (sub != null) Text(sub, style = T.sans(12), color = C.muted)
        }
        if (selected) Icon(Ic.CHECK, C.coral, 20.dp, stroke = 2.4f)
    }
}

@Composable
fun SoundsScreen(nav: Nav, host: Host, id: String) {
    val ctx = LocalContext.current
    tick()
    val sounds = remember { Sounds.system(ctx) }
    val a = Store.alarm(ctx, id)
    DisposableEffect(Unit) { onDispose { Preview.stop() } }
    if (a == null) { LaunchedEffect(Unit) { nav.back() }; return }
    SubPage("Alarm *sound*", nav) {
        item {
            Group {
                SoundRow("Default alarm", Sounds.defaultUri(ctx), a.sound == null, "whatever the phone's alarm sound is") { host.save(a.copy(sound = null, soundName = null)) }
                Divider()
                if (Store.soundFile(ctx, id).exists()) {
                    SoundRow(if (a.sound == "file") a.soundName ?: "Your file" else "Your file", Uri.fromFile(Store.soundFile(ctx, id)), a.sound == "file", "yours") { host.save(a.copy(sound = "file", soundName = "Your file")) }
                    Divider()
                }
                ValueRow("Add your own file…", "+") { host.pickSound(a) }
            }
        }
        item { SectionHeader("This phone's *sounds*") }
        item {
            Group {
                sounds.forEachIndexed { i, (title, uri) ->
                    if (i > 0) Divider()
                    SoundRow(title, Uri.parse(uri), a.sound == uri) { host.save(a.copy(sound = uri, soundName = title)) }
                }
            }
        }
    }
}
