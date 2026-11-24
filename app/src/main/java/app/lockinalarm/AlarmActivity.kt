package app.lockinalarm

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lockinalarm.ui.BigButton
import app.lockinalarm.ui.C
import app.lockinalarm.ui.Inter
import app.lockinalarm.ui.LockInTheme
import app.lockinalarm.ui.Pill
import app.lockinalarm.ui.T
import app.lockinalarm.ui.starred
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Full-screen, over the lock screen: sunrise, the ring itself, and the awake check. */
class AlarmActivity : ComponentActivity() {
    private val message = mutableStateOf<String?>(null)
    private val nfcOn = mutableStateOf(true)
    private var lastUid: String? = null
    private var lastAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (Store.session(this@AlarmActivity).phase == Phase.SUNRISE) finish()
                else Toast.makeText(this@AlarmActivity, "Nice try 😈 Go scan the tag", Toast.LENGTH_SHORT).show()
            }
        })
        setContent { LockInTheme { Screen() } }
    }

    /** NFC switched off/on (or a restart of the NFC stack) drops reader mode: turn it back on. */
    private val nfcState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, 0)
            nfcOn.value = state == NfcAdapter.STATE_ON
            if (state == NfcAdapter.STATE_ON) startReader()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(nfcState, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), RECEIVER_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(nfcState)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!onScreen(Store.session(this).phase)) { finish(); return }
        nfcOn.value = Checks.nfc(this)?.isEnabled == true
        startReader()
        Notify.shown(this, true)
    }

    override fun onPause() {
        super.onPause()
        Checks.nfc(this)?.disableReaderMode(this)
        Notify.shown(this, false)
    }

    private fun onScreen(p: Phase) = p == Phase.RINGING || p == Phase.AWAKE_ASK || p == Phase.SUNRISE

    private fun startReader() {
        Checks.nfc(this)?.enableReaderMode(this, { tag ->
            val uid = uidHex(tag.id)
            runOnUiThread {
                // one touch can be reported twice
                val now = System.currentTimeMillis()
                if (uid == lastUid && now - lastAt < 2000) return@runOnUiThread
                lastUid = uid; lastAt = now
                show(scanned(uid))
            }
        }, READER_FLAGS, null)
    }

    /** Says what happened and leaves if there's nothing left to do here. */
    private fun show(st: Step) {
        val text = said(this, st)
        if (onScreen(st.session.phase)) message.value = text
        else {
            text?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            finish()
        }
    }

    private fun send(e: Event) {
        val st = Engine.dispatch(applicationContext, e)
        show(st)
    }

    private fun photo(id: String): ImageBitmap? {
        val f = Store.photoFile(this, id)
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 1080 && o.outHeight / (sample * 2) >= 1080) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } catch (e: Exception) { Log.w(TAG, "photo: $e"); null }
    }

    @Composable
    private fun Screen() {
        @Suppress("UNUSED_EXPRESSION") Store.changed.collectAsState().value
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var typing by remember { mutableStateOf(false) }
        val s = Store.session(this)
        val a = s.alarmId?.let { Store.alarm(this, it) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                val cur = Store.session(this@AlarmActivity)
                // the system alarm does this too; doing it here keeps the screen exact
                if (cur.phase == Phase.AWAKE_ASK && now >= cur.until) Engine.dispatch(applicationContext, Event.Tick)
                if (!onScreen(Store.session(this@AlarmActivity).phase)) finish()
                delay(500)
            }
        }
        LaunchedEffect(s.phase, s.alarmId, s.ringSince) { message.value = null; typing = false }
        brightness(if (s.phase == Phase.SUNRISE) sun(s, now) else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        if (a == null) return
        when {
            s.phase == Phase.SUNRISE -> Sunrise(sun(s, now), s.until)
            s.phase == Phase.AWAKE_ASK -> StillUp(a, s, now)
            s.phase == Phase.RINGING && typing -> Typing({ typing = false }) { send(Event.ExitTyped) }
            s.phase == Phase.RINGING -> Ringing(a, s, now) { typing = true }
        }
    }

    private fun sun(s: Session, now: Long) = if (s.until <= s.startedAt) 1f else ((now - s.startedAt).toFloat() / (s.until - s.startedAt)).coerceIn(0.01f, 1f)

    private fun brightness(v: Float) {
        if (window.attributes.screenBrightness != v) window.attributes = window.attributes.apply { screenBrightness = v }
    }

    @Composable
    private fun clock(now: Long) = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

    @Composable
    private fun Sunrise(p: Float, ringAt: Long) {
        fun mix(a: Color, b: Color) = androidx.compose.ui.graphics.lerp(a, b, p)
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(mix(Color(0xFF0B0B16), Color(0xFFFFE9B8)), mix(Color(0xFF2A0F0A), Color(0xFFFF9A5C))))).systemBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            val fg = if (p > 0.45f) C.ink else Color(0xFFCFA58F)
            Text(clock(System.currentTimeMillis()), style = T.serif(80), color = fg)
            Text(starred("Rings at *${clock(ringAt)}*"), style = T.serif(26), color = fg)
            Spacer(Modifier.height(8.dp))
            Text(message.value ?: "Already up? Scan your tag and it won't ring.", style = T.sans(15), color = fg, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun StillUp(a: AlarmDef, s: Session, now: Long) {
        Column(
            Modifier.fillMaxSize().background(C.bg).systemBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            Text(starred("Still *up*?"), style = T.serif(64))
            Text("${((s.until - now + 999) / 1000).coerceAtLeast(0)}", style = T.serif(90), color = C.coral)
            Text("seconds, then it rings again without snoozes", style = T.sans(15), color = C.muted, textAlign = TextAlign.Center)
            message.value?.let { Text(it, style = T.sans(15), color = C.bad, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(10.dp))
            if (a.awakeScan) { Text(starred("Scan a *tag* to answer"), style = T.serif(28)); UnlockAndNfc() }
            else BigButton("I'm *up*") { send(Event.Awake) }
        }
    }

    /** What's in the way of a scan: the lock screen or NFC being off. */
    @Composable
    private fun UnlockAndNfc() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) BigButton("Unlock to *scan*", color = Color.White, textColor = C.ink) { km.requestDismissKeyguard(this, null) }
        else if (!nfcOn.value && Checks.nfc(this) != null) BigButton("Turn on *NFC*", color = Color.White, textColor = C.ink) {
            try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (e: Exception) { Log.w(TAG, "nfc settings: $e") }
        }
    }

    @Composable
    private fun Ringing(a: AlarmDef, s: Session, now: Long, onExit: () -> Unit) {
        val bg = remember(a.id) { photo(a.id) }
        val km = getSystemService(KeyguardManager::class.java)
        val left = Engine.wake(this).snoozeLeft(s, a)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bg?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillWidth, alignment = Alignment.Center, modifier = Modifier.fillMaxSize()) }
            // darken top and bottom so the text stays readable on any picture
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.75f), 0.3f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f),
            )))
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clock(now), style = T.serif(80), color = Color.White)
                Text(if (s.test) "Test ring" else a.name, style = T.sans(15), color = Color(0xFFE6E4DF))
                Spacer(Modifier.weight(1f))
                Text(a.text, fontFamily = Inter, fontSize = 72.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, lineHeight = 74.sp, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                val target = s.need.firstOrNull()?.let { "*${Store.tagName(this@AlarmActivity, it)}*" + if (s.need.size > 1) " (${s.need.size} to go)" else "" } ?: "your *tag*"
                Text(starred(if (km.isKeyguardLocked) "Unlock, then scan $target" else "Scan $target to stop it"), style = T.serif(26), color = Color(0xFFE6E4DF), textAlign = TextAlign.Center)
                if (now < s.snoozeArmedUntil) Text("Snooze: scan a tag within ${(s.snoozeArmedUntil - now + 999) / 1000} s", style = T.sans(15), color = C.pink)
                else message.value?.let { Spacer(Modifier.height(10.dp)); Text(it, style = T.sans(15), color = C.bad, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(20.dp))
                UnlockAndNfc()
                Spacer(Modifier.height(12.dp))
                Pill(
                    when {
                        left > 0 -> "Snooze ${a.snoozes[s.snoozesUsed]} min" + (if (a.snoozeScan && !s.test) " (scan)" else "") + " · $left left"
                        s.awakeRound > 0 -> "You fell back asleep. No snoozes 😈"
                        else -> "No snoozes left 😈"
                    },
                    color = if (left > 0) Color.White else C.muted, borderColor = Color.White.copy(alpha = 0.5f),
                    onClick = if (left > 0) ({ send(Event.Snooze) }) else null,
                )
                if (s.test) { Spacer(Modifier.height(10.dp)); Pill("End test", color = Color.White, onClick = { send(Event.EndTest) }) }
                else if (Engine.wake(this@AlarmActivity).exitShown(s, now)) { Spacer(Modifier.height(10.dp)); Pill("No tag here? The slow way out", color = C.muted, borderColor = C.lineHi, onClick = onExit) }
            }
        }
    }

    /** About five minutes of typing. One character at a time, so pasting and word suggestions don't work. */
    @Composable
    private fun Typing(onBack: () -> Unit, onDone: () -> Unit) {
        val todo = remember { Sentences.pick() }
        var i by remember { mutableIntStateOf(0) }
        var v by remember { mutableStateOf("") }
        val target = todo[i]
        Column(
            Modifier.fillMaxSize().background(C.bg).systemBarsPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(starred("The *slow* way out"), style = T.serif(32))
            Text("Sentence ${i + 1} of ${todo.size}. Scanning your tag still works.", style = T.sans(14), color = C.muted)
            Text(target, style = T.sans(20, 500))
            OutlinedTextField(
                v, { n ->
                    if (n.length <= v.length + 1 && target.startsWith(n)) {
                        v = n
                        if (n == target) { v = ""; if (i + 1 < todo.size) i++ else onDone() }
                    }
                },
                modifier = Modifier.fillMaxWidth(), textStyle = T.sans(18),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.coral, unfocusedBorderColor = C.lineHi, cursorColor = C.coral),
            )
            Text("Wrong letters are ignored.", style = T.sans(13), color = C.muted)
            Pill("Back", onClick = onBack)
        }
    }
}

const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

fun uidHex(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

object Sentences {
    private val ALL = listOf(
        "i am typing this because i do not want to walk to my tag right now",
        "the bed is warm but the day is longer than this moment of comfort",
        "every minute i spend on this sentence is a minute i could be awake",
        "somewhere in this room there is a sticker that would end this noise",
        "the person who set this alarm last night had a plan for this morning",
        "standing up takes two seconds and typing all of this takes minutes",
        "a glass of cold water works better than another hour of bad sleep",
        "nobody has ever regretted being awake ten minutes after getting up",
        "the snooze button is a loan that the rest of the day has to pay back",
        "my feet on the floor would be the fastest way out of this situation",
        "this is the slow way out and it was always meant to be a slow one",
        "by the time i finish this i will be too awake to go back to sleep",
        "light on, blanket off, feet down, and then everything gets easier",
        "i promised myself this morning and i am going to keep that promise",
        "the streak is gone today but tomorrow is a good day to start again",
        "a spare tag in the kitchen drawer would have been a very good idea",
    )
    /** About 750 characters: five minutes for sleepy thumbs. */
    fun pick(): List<String> = ALL.shuffled().take(11)
}
