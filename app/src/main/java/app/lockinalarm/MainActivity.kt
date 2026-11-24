package app.lockinalarm

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import app.lockinalarm.ui.App
import app.lockinalarm.ui.Host
import app.lockinalarm.ui.LockInTheme
import app.lockinalarm.ui.Nav
import java.io.File
import java.io.IOException
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val pairing = mutableStateOf(false)
    private val nav = Nav("alarms")
    /** Which alarm the open picker is for (survives the activity being recreated meanwhile). */
    private var pickFor: String? = null

    private val soundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val a = pickFor?.let { Store.alarm(this, it) }
        if (uri != null && a != null) {
            val no = Engine.soundLocked(this, a.id)
            if (no != null) toast(no)
            else copyIn(uri, Store.soundFile(this, a.id)) { name -> save(a.copy(sound = "file", soundName = name.take(60))) }
        }
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pickFor
        if (uri != null && id != null) copyIn(uri, Store.photoFile(this, id)) { Store.bump() }
    }
    private val packPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importPack) }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { Store.bump() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        pickFor = savedInstanceState?.getString("pickFor")
        if (savedInstanceState == null) debugHooks(intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val host = Host(
            pairing = pairing,
            pair = ::pair,
            pickTime = { minutes, done -> TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, m -> done(h * 60 + m) }, minutes / 60, minutes % 60, true).show() },
            pickDate = { done ->
                val t = LocalDate.now()
                DatePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, d -> done(LocalDate.of(y, m + 1, d)) }, t.year, t.monthValue - 1, t.dayOfMonth)
                    .apply { datePicker.minDate = System.currentTimeMillis() }.show()
            },
            pickSound = { pickFor = it.id; soundPicker.launch(arrayOf("audio/*")) },
            pickPhoto = { pickFor = it.id; photoPicker.launch(arrayOf("image/*")) },
            save = ::save,
            toast = ::toast,
            fix = { c -> if (c.fix == null) toast(c.hint) else try { startActivity(c.fix) } catch (e: Exception) { toast("Open the phone settings for this") } },
            exportPack = ::exportPack,
            importPack = { if (Engine.anyLocked(this)) toast("Locked until it rings 🔒") else packPicker.launch(arrayOf("*/*")) },
        )
        setContent { LockInTheme { App(host, nav) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pickFor", pickFor)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugHooks(intent)
    }

    override fun onResume() {
        super.onResume()
        Engine.rearm(this)
        Store.bump() // permissions may have changed in the settings
        val p = Store.session(this).phase
        if (p == Phase.RINGING || p == Phase.AWAKE_ASK) startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        updateReader()
    }

    override fun onPause() {
        super.onPause()
        Checks.nfc(this)?.disableReaderMode(this)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun save(a: AlarmDef) { Engine.save(this, a)?.let { toast(it); Store.bump() } }

    // ---------- NFC ----------
    private fun pair(on: Boolean) {
        val nfc = Checks.nfc(this)
        if (on && nfc == null) { toast("This phone has no NFC"); return }
        if (on && !nfc!!.isEnabled) { toast("Turn on NFC first"); return }
        pairing.value = on
        updateReader()
    }

    /** Reads tags only while pairing or while a wake-up is going on (a scan here counts too). */
    private fun updateReader() {
        val nfc = Checks.nfc(this) ?: return
        if (!pairing.value && !Store.session(this).active) { nfc.disableReaderMode(this); return }
        nfc.enableReaderMode(this, { tag ->
            val uid = uidHex(tag.id)
            runOnUiThread { if (pairing.value) paired(uid) else said(this, scanned(uid))?.let(::toast) }
        }, READER_FLAGS, null)
    }

    private fun paired(uid: String) {
        pair(false)
        val tags = Store.tags(this)
        tags.firstOrNull { it.uid == uid }?.let { toast("Already paired: ${it.name}"); return }
        // phones and some cards make up a new ID (08...) every time: useless as an alarm tag
        if (uid.length == 8 && uid.startsWith("08")) { toast("This one changes its ID every time, use another tag"); return }
        Store.setTags(this, tags + TagRef(uid, "Tag ${tags.size + 1}"))
        toast("Paired. Give it a name under Tags")
        Log.i(TAG, "paired a tag (${uid.length / 2} byte id)")
    }

    // ---------- files ----------
    /** Copies a picked file into the app's own storage, so access never expires. The old one stays if that fails. */
    private fun copyIn(uri: Uri, to: File, done: (String) -> Unit) {
        val tmp = File(to.path + ".tmp")
        try {
            contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(to)) throw IOException("rename failed")
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: to.name
            done(name)
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "copy failed: $e")
            toast("Couldn't read that file")
        }
    }

    private fun exportPack() {
        try {
            val f = File(File(cacheDir, "share").apply { mkdirs() }, "my-alarm.lockin")
            val items = Store.alarms(this).map { a ->
                fun small(x: File) = x.takeIf { it.exists() && it.length() <= Pack.MAX_FILE }?.readBytes()
                Pack.Item(a, if (a.sound == "file") small(Store.soundFile(this, a.id)) else null, small(Store.photoFile(this, a.id)))
            }
            if (items.isEmpty()) { toast("No alarms to share yet"); return }
            f.outputStream().use { Pack.write(items, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.share", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share my setup"))
        } catch (e: Exception) { Log.w(TAG, "export: $e"); toast("Couldn't make the file") }
    }

    private fun importPack(uri: Uri) {
        if (Engine.anyLocked(this)) { toast("Locked until it rings 🔒"); return }
        try {
            val items = contentResolver.openInputStream(uri)!!.use { Pack.read(it, Store::newId) }
            items.forEach { i ->
                i.sound?.let { Store.soundFile(this, i.def.id).writeBytes(it) }
                i.photo?.let { Store.photoFile(this, i.def.id).writeBytes(it) }
                Engine.save(this, i.def)
            }
            toast("Added ${items.size} alarm${if (items.size == 1) "" else "s"}, switched off")
            nav.tab("alarms")
        } catch (e: Exception) { Log.w(TAG, "import: $e"); toast("Not a Lock In setup file") }
    }

    // ---------- PC hooks, debug builds only ----------
    /**
     * adb shell am start -n app.lockinalarm/.MainActivity ...
     *   --es screen <route>
     *   --ei test_sec 10 [--ez loud true] | --ez end_test true | --ei ring_in <sec> (REAL ring of the first alarm)
     *   --es tag <uid> (as if scanned; emulator only)
     */
    private fun debugHooks(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        val first = Store.alarms(this).firstOrNull()
        if (i.hasExtra("test_sec") && first != null) Engine.test(this, first.id, i.getIntExtra("test_sec", 10), !i.getBooleanExtra("loud", false))
        if (i.getBooleanExtra("end_test", false)) Engine.dispatch(this, Event.EndTest)
        i.getStringExtra("screen")?.let { if (":" in it) nav.go(it) else nav.tab(it) }
        val emulator = android.os.Build.HARDWARE in listOf("ranchu", "goldfish")
        if (emulator) DebugTools.run(this, i)
    }
}
