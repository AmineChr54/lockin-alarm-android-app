package app.lockinalarm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lockinalarm.AlarmDef
import app.lockinalarm.Check
import app.lockinalarm.Store
import java.time.LocalDate
import java.util.Locale

/** What the screens need from MainActivity (NFC, system pickers, sharing). */
class Host(
    val pairing: State<Boolean>,
    val pair: (Boolean) -> Unit,
    val pickTime: (minutes: Int, (Int) -> Unit) -> Unit,
    val pickDate: ((LocalDate) -> Unit) -> Unit,
    val pickSound: (AlarmDef) -> Unit,
    val pickPhoto: (AlarmDef) -> Unit,
    /** Saves through the Engine and says so if it refuses. */
    val save: (AlarmDef) -> Unit,
    val toast: (String) -> Unit,
    val fix: (Check) -> Unit,
    val exportPack: () -> Unit,
    val importPack: () -> Unit,
)

/** Navigation: a stack of route strings. Tabs reset it; sub-screens push. */
class Nav(start: String) {
    val stack = mutableStateListOf(start)
    val current get() = stack.last()
    var forward by mutableStateOf(true)
    fun go(r: String) { forward = true; stack.add(r) }
    fun tab(r: String) { forward = true; stack.clear(); stack.add(r) }
    fun back(): Boolean { if (stack.size <= 1) return false; forward = false; stack.removeAt(stack.lastIndex); return true }
}

/** Recompose whenever anything in the Store changes. */
@Composable
fun tick(): Long = Store.changed.collectAsState().value

fun hhmm(minutes: Int) = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

private val TABS = listOf("alarms", "tags", "stats", "settings")

@Composable
fun App(host: Host, nav: Nav) {
    tick()
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    val tabs = listOf(
        NavItem("alarms", Ic.ALARM, "Alarms"), NavItem("tags", Ic.TAG, "Tags"),
        NavItem("stats", Ic.CHART, "Stats"), NavItem("settings", Ic.SETTINGS, "Settings"),
    )
    val route = nav.current
    val onTab = route in TABS
    Box(Modifier.fillMaxSize().background(C.bg)) {
        AnimatedContent(
            route, label = "screen",
            transitionSpec = {
                val dir = if (nav.forward) 1 else -1
                if (initialState in TABS && targetState in TABS) fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                else (slideInHorizontally(tween(300)) { it / 4 * dir } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(260)) { -it / 6 * dir } + fadeOut(tween(200)))
            },
        ) { r ->
            when {
                r == "tags" -> TagsScreen(host)
                r == "stats" -> StatsScreen()
                r == "settings" -> SettingsScreen(host)
                r.startsWith("alarm:") -> EditScreen(nav, host, r.removePrefix("alarm:"))
                r.startsWith("sounds:") -> SoundsScreen(nav, host, r.removePrefix("sounds:"))
                else -> AlarmsScreen(nav, host)
            }
        }
        AnimatedVisibility(
            onTab, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            enter = slideInVertically { it * 2 } + fadeIn(), exit = slideOutVertically { it * 2 } + fadeOut(),
        ) { PillNav(tabs, if (onTab) route else "alarms") { nav.tab(it) } }
        PairSheet(host, Modifier.align(Alignment.BottomCenter))
    }
}

/** Standard scrolling page: status-bar padding, 16 dp sides, room for the nav at the bottom. */
@Composable
fun Page(bottomRoom: Boolean = true, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (bottomRoom) 120.dp else 60.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** A sub-screen with the round back button. */
@Composable
fun SubPage(title: String, nav: Nav, action: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    Page(bottomRoom = false) {
        item { TopBar(title, { nav.back() }, action) }
        content()
    }
}

@Composable
fun Title(text: String) = Text(starred(text), style = T.serif(40), modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))

@Composable
fun Note(text: String) = Text(text, style = T.sans(13), color = C.muted, modifier = Modifier.padding(horizontal = 4.dp))

@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) = TCard(pad = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { content() }

@Composable
private fun PairSheet(host: Host, modifier: Modifier) {
    AnimatedVisibility(host.pairing.value, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(C.cardHi).navigationBarsPadding().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Ripple()
            Text(starred("Hold a tag to the *back*"), style = T.serif(28))
            Text("Any NFC sticker or card with a fixed ID works. Nothing is written to it.", style = T.sans(14), color = C.muted, textAlign = TextAlign.Center)
            BigButton("Cancel", color = C.card, textColor = C.text) { host.pair(false) }
        }
    }
}

/** Pulsing NFC rings for "hold a tag to the phone". */
@Composable
fun Ripple() {
    val inf = rememberInfiniteTransition(label = "ripple")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(Modifier.size(120.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        for (i in 0..2) {
            val p = (t + i / 3f) % 1f
            drawCircle(C.coral, radius = size.minDimension / 2 * (0.25f + 0.75f * p), center = c, alpha = (1 - p) * 0.9f, style = Stroke(3.dp.toPx()))
        }
        drawCircle(C.coral, radius = size.minDimension * 0.12f, center = c)
    }
}
