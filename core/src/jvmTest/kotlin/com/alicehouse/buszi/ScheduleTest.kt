package com.alicehouse.buszi

import com.alicehouse.buszi.data.CsvTable
import com.alicehouse.buszi.data.Departure
import com.alicehouse.buszi.data.PatternBuilder
import com.alicehouse.buszi.data.PatternCodec
import com.alicehouse.buszi.data.ServiceCalendar
import com.alicehouse.buszi.data.ServiceTime
import com.alicehouse.buszi.data.dayStart
import com.alicehouse.buszi.data.StopTimeRow
import com.alicehouse.buszi.data.mergeDepartures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipFile

class ScheduleTest {
    private val edmonton = ZoneId.of("America/Edmonton")

    @Test
    fun parsesGtfsTimes() {
        assertEquals(0, ServiceTime.parse("00:00:00"))
        assertEquals(5 * 3600 + 2 * 60, ServiceTime.parse(" 5:02:00"))
        assertEquals(25 * 3600 + 10 * 60, ServiceTime.parse("25:10:00"))
        assertEquals(-1, ServiceTime.parse(""))
        assertEquals(-1, ServiceTime.parse("bad"))
    }

    @Test
    fun serviceDayStartIsNoonMinus12hOnDstDays() {
        for (d in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 11))) {
            val noon = d.atTime(12, 0).atZone(edmonton).toEpochSecond()
            assertEquals(noon, ServiceTime.dayStart(d, edmonton) + 12 * 3600)
        }
        // A normal day: dayStart is local midnight.
        val d = LocalDate.of(2026, 11, 11)
        assertEquals(d.atStartOfDay(edmonton).toEpochSecond(), ServiceTime.dayStart(d, edmonton))
    }

    @Test
    fun codecRoundTrip() {
        val refs = intArrayOf(1, 300, 70000, 5)
        val offs = intArrayOf(0, 60, 61, 20000)
        val board = booleanArrayOf(true, false, true, false)
        val got = ArrayList<Triple<Int, Int, Boolean>>()
        PatternCodec.decode(PatternCodec.encode(refs, offs, board)) { r, o, b -> got.add(Triple(r, o, b)) }
        assertEquals(refs.indices.map { Triple(refs[it], offs[it], board[it]) }, got)
    }

    @Test
    fun tripsWithSameShapeShareOnePattern() {
        val created = ArrayList<PatternBuilder.Pattern>()
        val b = PatternBuilder { created.add(it) }
        fun trip(start: Int) = mutableListOf(
            StopTimeRow(2, 11, start + 120, false),
            StopTimeRow(1, 10, start, false), // out of order on purpose
            StopTimeRow(3, 12, -1, false),     // missing time -> interpolated
            StopTimeRow(4, 13, start + 360, false),
        )
        val a = b.addTrip("8", 0, "Downtown", trip(6 * 3600))
        val c = b.addTrip("8", 0, "Downtown", trip(7 * 3600))
        val other = b.addTrip("8", 1, "Abbottsfield", trip(6 * 3600))
        assertEquals(a.pattern, c.pattern)
        assertEquals(6 * 3600, a.start)
        assertEquals(7 * 3600, c.start)
        assertTrue(other.pattern != a.pattern)
        assertEquals(2, created.size)

        val stops = ArrayList<Triple<Int, Int, Boolean>>()
        PatternCodec.decode(created[0].stops) { r, o, can -> stops.add(Triple(r, o, can)) }
        assertEquals(
            listOf(Triple(10, 0, true), Triple(11, 120, true), Triple(12, 240, true), Triple(13, 360, false)),
            stops,
        )
    }

    @Test
    fun calendarWeeklyAndExceptions() {
        val cal = ServiceCalendar()
        val weekdays = BooleanArray(7) { it < 5 }
        cal.addWeekly("WK", weekdays, kotlinx.datetime.LocalDate(2026, 11, 9), kotlinx.datetime.LocalDate(2026, 11, 15)) // Mon..Sun
        cal.addException("WK", 20261111, 2) // Remembrance Day removed
        cal.addException("HOL", 20261111, 1)
        val got = HashSet<Pair<Int, Int>>()
        cal.forEach { d, s -> got.add(d to s) }
        val wk = cal.id("WK"); val hol = cal.id("HOL")
        assertEquals(setOf(20261109 to wk, 20261110 to wk, 20261112 to wk, 20261113 to wk, 20261111 to hol), got)
    }

    @Test
    fun mergePrefersRealtimeAndDropsCanceled() {
        val live = listOf(Departure("A", "S", 1000, true))
        val sched = listOf(
            Departure("A", "S", 900, false),  // same trip as live -> dropped
            Departure("B", "S", 950, false),  // canceled -> dropped
            Departure("C", "S", 1100, false), // kept
            Departure("D", "S", 800, false),  // live says this stop was already passed -> dropped
        )
        val out = mergeDepartures(live, sched, liveKeys = setOf("A|S", "D|S"), canceled = setOf("B"))
        assertEquals(listOf("A" to true, "C" to false), out.map { it.tripId to it.realtime })
    }

    /** Runs the real Edmonton stop_times through the compressor. Set GTFS_ZIP to enable. */
    @Test
    fun compressesRealFeed() {
        val zipPath = System.getenv("GTFS_ZIP") ?: ""
        assumeTrue(zipPath.isNotEmpty() && File(zipPath).exists())
        ZipFile(zipPath).use { z ->
            val trips = HashMap<String, Triple<String, Int, String>>()
            CsvTable(z.getInputStream(z.getEntry("trips.txt")).reader()).let { t ->
                val id = t.col("trip_id"); val r = t.col("route_id"); val d = t.col("direction_id"); val h = t.col("trip_headsign")
                t.forEachRow { trips[it[id]] = Triple(it[r], it[d].toIntOrNull() ?: 0, it[h]) }
            }
            val stopRef = HashMap<String, Int>()
            var blobBytes = 0L
            val b = PatternBuilder { blobBytes += it.stops.size }
            val t0 = System.currentTimeMillis()
            var rows = 0
            CsvTable(z.getInputStream(z.getEntry("stop_times.txt")).reader().buffered(64 * 1024)).let { t ->
                val tid = t.col("trip_id"); val seq = t.col("stop_sequence"); val sid = t.col("stop_id")
                val dep = t.col("departure_time"); val pu = t.col("pickup_type")
                var cur: String? = null
                val buf = ArrayList<StopTimeRow>()
                fun flush() { cur?.let { id -> trips[id]?.let { b.addTrip(it.first, it.second, it.third, buf) } } }
                t.forEachRow { r ->
                    rows++
                    if (r[tid] != cur) { flush(); buf.clear(); cur = r[tid] }
                    val ref = stopRef.getOrPut(r[sid]) { stopRef.size + 1 }
                    buf.add(StopTimeRow(r[seq].toInt(), ref, ServiceTime.parse(r[dep]), r[pu] == "1"))
                }
                flush()
            }
            val ms = System.currentTimeMillis() - t0
            println("stop_times rows=$rows trips=${trips.size} patterns=${b.count} blobKB=${blobBytes / 1024} time=${ms}ms")
            assertTrue(b.count in 1 until trips.size / 5)
            assertTrue(blobBytes < 2_000_000)
        }
    }
}
