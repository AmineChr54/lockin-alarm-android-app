package app.lockinalarm

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The .lockin sharing pack: a zip with alarms.json, sound_<i>, photo_<i>. Never any tag UIDs or stats. Pure. */
object Pack {
    const val MAX_JSON = 256 * 1024
    const val MAX_FILE = 20 * 1024 * 1024
    const val MAX_TOTAL = 60 * 1024 * 1024
    const val MAX_ALARMS = 20
    private val FILE = Regex("^(sound|photo)_(\\d{1,2})$")

    class Item(val def: AlarmDef, val sound: ByteArray?, val photo: ByteArray?)

    fun write(items: List<Item>, out: OutputStream) = ZipOutputStream(out).use { z ->
        fun put(name: String, b: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() }
        val list = items.take(MAX_ALARMS)
        val arr = JSONArray()
        list.forEach {
            // another phone's ringtone uri means nothing there; own files travel along
            val d = it.def.copy(on = false, tags = emptyList(), dates = emptyMap(), sound = if (it.sound != null) "file" else null, soundName = if (it.sound != null) it.def.soundName else null)
            arr.put(d.json())
        }
        put("alarms.json", JSONObject().put("lockin", 1).put("alarms", arr).toString().toByteArray())
        list.forEachIndexed { i, it ->
            it.sound?.let { b -> put("sound_$i", b) }
            it.photo?.let { b -> put("photo_$i", b) }
        }
    }

    /** Throws IOException on anything that isn't a sane pack. Everything comes back switched off and without tags. */
    fun read(input: InputStream, newId: () -> String): List<Item> {
        var json: ByteArray? = null
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.name == "alarms.json" -> { if (json != null) throw IOException("broken pack"); json = limited(z, MAX_JSON) }
                    FILE.matches(e.name) -> {
                        if (files.size >= MAX_ALARMS * 2 || e.name in files) throw IOException("broken pack")
                        files[e.name] = limited(z, minOf(MAX_FILE, MAX_TOTAL - files.values.sumOf { it.size }))
                    }
                    else -> throw IOException("unexpected file in the pack")
                }
            }
        }
        val arr = try {
            val o = JSONObject(String(json ?: throw IOException("not a Lock In pack")))
            if (o.optInt("lockin") != 1) throw IOException("made by a newer version")
            o.getJSONArray("alarms")
        } catch (e: JSONException) { throw IOException("broken pack") }
        if (arr.length() == 0 || arr.length() > MAX_ALARMS) throw IOException("broken pack")
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: throw IOException("broken pack")
            val sound = files["sound_$i"]
            val d = AlarmDef.of(o.put("id", newId())).copy(on = false, tags = emptyList(), dates = emptyMap())
            Item(d.copy(sound = if (sound != null) "file" else null, soundName = if (sound != null) d.soundName ?: "Shared sound" else null), sound, files["photo_$i"])
        }
    }

    private fun limited(s: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) throw IOException("file in the pack is too big")
            out.write(buf, 0, n)
        }
    }
}
