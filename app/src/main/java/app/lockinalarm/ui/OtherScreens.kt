package app.lockinalarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lockinalarm.Checks
import app.lockinalarm.Engine
import app.lockinalarm.Record
import app.lockinalarm.Schedule
import app.lockinalarm.Stats
import app.lockinalarm.Store
import app.lockinalarm.TagRef
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ================= tags =================
@Composable
fun TagsScreen(host: Host) {
    val ctx = LocalContext.current
    tick()
    var edit by remember { mutableStateOf<TagRef?>(null) }
    var remove by remember { mutableStateOf<TagRef?>(null) }
    val tags = Store.tags(ctx)
    Page {
        item { Title("Your *tags*") }
        item { Note(if (tags.isEmpty()) "Stick an NFC tag somewhere you have to get up for: bathroom mirror, coffee machine, front door." else "Keep a spare somewhere safe. Pair it here and add it to the alarm.") }
        if (tags.isNotEmpty()) item {
            Group {
                tags.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    val used = Store.alarms(ctx).filter { t.uid in it.tags }.joinToString { it.name }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(t.name, style = T.sans(15))
                            Text(if (used.isEmpty()) "not used" else "stops $used", style = T.sans(12), color = C.muted)
                        }
                        CircleButton(Ic.EDIT, "Rename ${t.name}", size = 38.dp) { edit = t }
                        CircleButton(Ic.TRASH, "Remove ${t.name}", size = 38.dp) { remove = t }
                    }
                }
            }
        }
        item { BigButton("Pair a *tag*") { host.pair(true) } }
        item { Note("Lost every tag? After a minute of ringing the alarm screen offers a slow way out: typing sentences for about five minutes.") }
    }
    edit?.let { t -> InputDialog("Tag *name*", t.name, hint = "Where it is, e.g. Bathroom.", onDismiss = { edit = null }) { v ->
        Store.setTags(ctx, Store.tags(ctx).map { if (it.uid == t.uid) it.copy(name = v.trim().take(24).ifEmpty { t.name }) else it }); edit = null
    } }
    remove?.let { t -> ChoiceDialog("Remove *${t.name}*?", listOf("Yes, remove it"), -1, { remove = null }) { remove = null; Engine.removeTag(ctx, t.uid)?.let(host.toast) } }
}

// ================= stats =================
/** Checked with the dataviz palette validator on the dark card: told apart with every kind of colour vision. */
private val CLEAN = Color(0xFF4E8FD0)
private val ROUGH = Color(0xFFE0583C)

private fun span(secs: Long) = when { secs < 0 -> "no scan"; secs < 60 -> "$secs s"; secs < 3600 -> "${secs / 60} min ${secs % 60} s"; else -> "${secs / 3600} h ${secs / 60 % 60} min" }

@Composable
fun StatsScreen() {
    val ctx = LocalContext.current
    tick()
    val zone = ZoneId.systemDefault()
    val all = Store.records(ctx)
    val days = Stats.days(all, zone)
    Page {
        item { Title("Your *mornings*") }
        if (all.isEmpty()) {
            item { Note("Nothing yet. Your first real alarm starts the streak: no snooze, no missed awake check.") }
            return@Page
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(C.pink).padding(18.dp)) {
                Text("NO-SNOOZE STREAK", style = T.label, color = C.ink.copy(alpha = 0.75f))
                val n = Stats.streak(days)
                Text(if (n == 1) "1 day" else "$n days", style = T.serif(60), color = C.ink)
                Text("Best ${Stats.best(days)} · best week ${Stats.bestWeek(days)} of 7", style = T.sans(13), color = C.ink)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("Typical time up", Stats.medianSecs(all.takeLast(30))?.let(::span) ?: "-", Modifier.weight(1f))
                Tile("Snoozes, 30 days", all.filter { it.at > System.currentTimeMillis() - 30L * 86_400_000 }.sumOf { it.snoozes }.toString(), Modifier.weight(1f))
            }
        }
        item { SectionHeader("Minutes until *scanned*") }
        item { MorningChart(all, zone) }
        item { SectionHeader("*Latest*") }
        item {
            Group {
                all.takeLast(14).reversed().forEachIndexed { i, r ->
                    if (i > 0) Divider()
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(Schedule.dateOf(r.at, zone).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)), style = T.sans(14), modifier = Modifier.weight(1f))
                        Text(describe(r), style = T.sans(14), color = C.muted)
                    }
                }
            }
        }
        item { Note("Stored on this phone only.") }
    }
}

private fun describe(r: Record) = listOfNotNull(
    if (r.missed) "missed, phone was off" else if (r.exit) "typed out" else if (r.early) "early" else span(r.scanSecs),
    if (r.snoozes > 0) "${r.snoozes}× snooze" else null,
    if (r.awakeMissed > 0) "${r.awakeMissed}× fell back asleep" else null,
).joinToString(" · ")

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) = TCard(modifier) {
    Text(label, style = T.sans(13), color = C.muted)
    Text(value, style = T.serif(30))
}

/** 30 days, one bar per day (the day's longest wake-up). Tap a bar for that day. */
@Composable
private fun MorningChart(all: List<Record>, zone: ZoneId) {
    val today = LocalDate.now()
    val from = today.minusDays(29)
    val byDay = all.filter { !it.missed }.groupBy { Schedule.dateOf(it.at, zone) }
    var sel by remember { mutableStateOf<LocalDate?>(null) }
    fun mins(d: LocalDate) = byDay[d]?.maxOf { it.scanSecs.coerceAtLeast(0) }?.div(60f)
    val top = (0..29).mapNotNull { mins(from.plusDays(it.toLong())) }.maxOrNull() ?: 0f
    val max = listOf(1f, 2f, 5f, 10f, 20f, 30f, 60f, 120f).firstOrNull { it >= top } ?: top
    TCard {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Key(CLEAN, "Clean"); Key(ROUGH, "Snoozed or slept on") }
        Row {
            Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text("${max.toInt()}", style = T.sans(11), color = C.muted)
                Text("0", style = T.sans(11), color = C.muted)
            }
            Canvas(
                Modifier.weight(1f).height(140.dp).padding(start = 8.dp).semantics { contentDescription = "Minutes until scanned, last 30 days" }
                    .pointerInput(all) { detectTapGestures { o -> sel = from.plusDays((o.x / size.width * 30).toLong().coerceIn(0, 29)) } },
            ) {
                for (g in listOf(0f, 0.5f, 1f)) drawLine(C.line, Offset(0f, size.height * g), Offset(size.width, size.height * g), 1f)
                val slot = size.width / 30
                val w = (slot - 2.dp.toPx()).coerceIn(2f, 24.dp.toPx())
                for (i in 0..29) {
                    val d = from.plusDays(i.toLong())
                    val m = mins(d) ?: continue
                    val h = (m / max * size.height).coerceIn(3.dp.toPx(), size.height)
                    val r = CornerRadius(minOf(4.dp.toPx(), w / 2))
                    val x = slot * i + (slot - w) / 2
                    val bar = Path().apply { addRoundRect(RoundRect(x, size.height - h, x + w, size.height, topLeftCornerRadius = r, topRightCornerRadius = r)) }
                    drawPath(bar, if (byDay.getValue(d).all(Stats::clean)) CLEAN else ROUGH, alpha = if (sel == null || sel == d) 1f else 0.45f)
                }
            }
        }
        Row {
            Text(from.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)), style = T.sans(11), color = C.muted, modifier = Modifier.weight(1f).padding(start = 18.dp))
            Text("today", style = T.sans(11), color = C.muted)
        }
        Text(sel?.let { d -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) + ": " + (byDay[d]?.joinToString("; ") { describe(it) } ?: "no alarm") } ?: "Tap a bar for the details.", style = T.sans(13))
    }
}

@Composable
private fun Key(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Text(label, style = T.sans(12), color = C.muted)
}

// ================= settings =================
@Composable
fun SettingsScreen(host: Host) {
    val ctx = LocalContext.current
    tick()
    var dialog by remember { mutableStateOf<String?>(null) }
    Page {
        item { Title("Settings") }
        item { SectionHeader("No *cheating*") }
        item {
            Group {
                val h = Store.lockHours(ctx)
                ValueRow("Commitment lock", if (h == 0) "off" else "$h h before", "then alarms can only get stricter") { dialog = "lock" }
            }
        }
        item { Note("It can't stop you from uninstalling the app or force-stopping it in the phone's settings. Nothing can.") }
        item { SectionHeader("At *bedtime*") }
        item {
            Group {
                val p = Store.preflightHours(ctx)
                ValueRow("Check before bed", if (p == 0) "off" else "$p h before", "a notification: all set, or what to fix") { dialog = "pre" }
            }
        }
        item { SectionHeader("This *phone*") }
        item {
            Group {
                Checks.setup(ctx).forEachIndexed { i, c ->
                    if (i > 0) Divider()
                    ValueRow(c.label, if (c.ok) "ok ✓" else "fix", c.hint) { host.fix(c) }
                }
            }
        }
        item { SectionHeader("*Share*") }
        item {
            Group {
                ValueRow("Share my setup", "send", "alarms, own sound, photo. Never your tags or stats") { host.exportPack() }
                Divider()
                ValueRow("Open a friend's setup", "open", "arrives switched off") { host.importPack() }
            }
        }
    }
    when (dialog) {
        "lock" -> listOf(0, 1, 2, 4, 6, 8, 10, 12).let { o -> ChoiceDialog("Commitment *lock*", o.map { if (it == 0) "Off" else "$it h before it rings" }, o.indexOf(Store.lockHours(ctx)), { dialog = null }) { Engine.setLockHours(ctx, o[it])?.let(host.toast); dialog = null } }
        "pre" -> listOf(0, 6, 7, 8, 9, 10).let { o -> ChoiceDialog("Check before *bed*", o.map { if (it == 0) "Off" else "$it h before the alarm" }, o.indexOf(Store.preflightHours(ctx)), { dialog = null }) { Store.setPreflightHours(ctx, o[it]); Engine.rearm(ctx); dialog = null } }
    }
}
