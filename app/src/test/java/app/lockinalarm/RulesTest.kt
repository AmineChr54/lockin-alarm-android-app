package app.lockinalarm

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RulesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun ms(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val a = AlarmDef("a", on = true, days = List(7) { 510 }, tags = listOf("t1"), route = Route.RANDOM, snoozes = listOf(5, 3), snoozeScan = true, awakeMin = 7, awakeScan = true, buildUp = BuildUp.FADE30)
    private fun refuse(n: AlarmDef?, now: Long = ms(18, 23), hours: Int = 10) = Lock.refuse(a, n, now, hours, zone)

    @Test fun lockWindow() {
        assertNull(refuse(null, ms(18, 12)))
        assertNull(refuse(null, hours = 0))
        assertNotNull(refuse(null))
        assertNotNull(refuse(null, ms(18, 22, 30))) // exactly 10 h before
    }

    @Test fun weakerIsRefused() {
        val d = LocalDate.of(2026, 11, 19)
        listOf(
            a.copy(on = false), a.copy(days = List(7) { 511 }), a.copy(dates = mapOf(d to -1)), a.copy(dates = mapOf(d to 600)), a.copy(days = List(7) { -1 }),
            a.copy(dates = mapOf(d to 10)), a.copy(tags = emptyList()), a.copy(tags = listOf("t1", "bed")), a.copy(tags = listOf("bed")), a.copy(route = Route.ANY),
            a.copy(snoozes = listOf(5, 3, 1)), a.copy(snoozes = listOf(5, 4)), a.copy(snoozeScan = false), a.copy(awakeMin = 0), a.copy(awakeMin = 10), a.copy(awakeScan = false),
            a.copy(sound = "silence"), a.copy(forceMax = false), a.copy(vibrate = false), a.copy(buildUp = BuildUp.STAGES),
        ).forEach { assertNotNull("$it", refuse(it)) }
    }

    @Test fun stricterIsFine() {
        listOf(
            a, a.copy(days = List(7) { 480 }), a.copy(dates = mapOf(LocalDate.of(2026, 11, 19) to 400)), a.copy(route = Route.ORDER), a.copy(snoozes = emptyList()),
            a.copy(snoozes = listOf(4, 1)), a.copy(awakeMin = 3), a.copy(buildUp = BuildUp.INSTANT), a.copy(text = "UP"), a.copy(name = "x"), a.copy(sunriseMin = 10),
        ).forEach { assertNull("$it", refuse(it)) }
    }

    // ---- stats ----
    private fun r(day: Int, snoozes: Int = 0, exit: Boolean = false, missed: Boolean = false) = Record("a", ms(day, 8, 30), 20, snoozes, 0, exit, false, missed)

    @Test fun streaks() {
        assertEquals(0, Stats.streak(emptyList()))
        // Mon..Wed clean, Thu snoozed, Fri clean, weekend without alarm, Mon clean
        val d = Stats.days(listOf(r(16), r(17), r(18), r(19, 1), r(20), r(23)), zone)
        assertEquals(2, Stats.streak(d))
        assertEquals(3, Stats.best(d))
        assertEquals(4, Stats.bestWeek(d))
        assertEquals(0, Stats.streak(Stats.days(listOf(r(16), r(17, exit = true)), zone)))
        assertEquals(0, Stats.streak(Stats.days(listOf(r(16), r(17, missed = true)), zone)))
        assertEquals(0, Stats.streak(Stats.days(listOf(r(16), r(16, 1)), zone))) // one bad alarm spoils the day
        val list = listOf(r(16), r(17, 2, true))
        assertEquals(list, Stats.of(JSONArray(Stats.json(list).toString())))
    }

    // ---- pack ----
    private fun pack(vararg i: Pack.Item) = ByteArrayOutputStream().also { Pack.write(i.toList(), it) }.toByteArray()
    private fun read(b: ByteArray) = Pack.read(ByteArrayInputStream(b)) { "new" }
    private fun zip(vararg f: Pair<String, ByteArray>) = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> f.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test fun packRoundTripStripsPrivateThings() {
        val b = pack(Pack.Item(a.copy(sound = "file", soundName = "s.mp3"), byteArrayOf(1, 2), byteArrayOf(3)), Pack.Item(a.copy(sound = "content://ring/5"), null, null))
        val json = ZipInputStream(ByteArrayInputStream(b)).let { z -> z.nextEntry; String(z.readBytes()) }
        assertFalse(json.contains("\"t1\"") || json.contains("content:"))
        val (x, y) = read(b)
        assertEquals("new", x.def.id)
        assertFalse(x.def.on)
        assertTrue(x.def.tags.isEmpty())
        assertEquals("file", x.def.sound)
        assertArrayEquals(byteArrayOf(1, 2), x.sound)
        assertArrayEquals(byteArrayOf(3), x.photo)
        assertNull(y.def.sound)
        assertEquals(a.days, y.def.days)
        assertEquals(a.snoozes, y.def.snoozes)
    }

    @Test fun badPacks() {
        val ok = """{"lockin":1,"alarms":[{"on":true,"tags":["x"],"future":{}}]}"""
        read(zip("alarms.json" to ok.toByteArray())).single().def.let { assertFalse(it.on); assertTrue(it.tags.isEmpty()) }
        listOf(
            "rubbish".toByteArray(), zip(), zip("alarms.json" to "{".toByteArray()), zip("alarms.json" to """{"lockin":2,"alarms":[{}]}""".toByteArray()),
            zip("alarms.json" to """{"lockin":1,"alarms":[]}""".toByteArray()), zip("alarms.json" to """{"lockin":1,"alarms":[5]}""".toByteArray()),
            zip("alarms.json" to ok.toByteArray(), "../evil" to byteArrayOf(1)), zip("alarms.json" to ByteArray(Pack.MAX_JSON + 1)),
            zip("alarms.json" to ok.toByteArray(), "sound_0" to ByteArray(Pack.MAX_FILE + 1)),
        ).forEachIndexed { i, b ->
            val accepted = try { read(b); true } catch (_: IOException) { false }
            if (accepted) fail("pack $i accepted")
        }
    }
}
