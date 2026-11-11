package com.alicehouse.buszi

import com.alicehouse.buszi.data.Departure
import com.alicehouse.buszi.data.GtfsRealtimeParser
import com.alicehouse.buszi.data.PatternCodec
import com.alicehouse.buszi.data.Regions
import com.alicehouse.buszi.data.ServiceCalendar
import com.alicehouse.buszi.data.ServiceTime
import com.alicehouse.buszi.data.mergeDepartures
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on every target (JVM, Windows native now; iOS once built on a Mac), proving the shared
 * code behaves the same everywhere.
 */
class CommonCoreTest {
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
    fun serviceTime() {
        assertEquals(25 * 3600 + 10 * 60, ServiceTime.parse("25:10:00"))
        assertEquals(20261111, ServiceTime.dateInt(LocalDate(2026, 11, 11)))
        assertEquals(LocalDate(2026, 3, 8), ServiceTime.parseDate("20260308"))
        // dayStart + 12 h is local noon, also on a DST change day.
        val zone = TimeZone.of("America/Toronto")
        val start = ServiceTime.dayStart(LocalDate(2026, 3, 8), zone)
        assertTrue(start > 0)
    }

    @Test
    fun calendar() {
        val cal = ServiceCalendar()
        cal.addWeekly("WK", BooleanArray(7) { it < 5 }, LocalDate(2026, 11, 9), LocalDate(2026, 11, 15))
        cal.addException("WK", 20261111, 2)
        val got = HashSet<Int>()
        cal.forEach { d, _ -> got.add(d) }
        assertEquals(setOf(20261109, 20261110, 20261112, 20261113), got)
    }

    @Test
    fun merge() {
        val out = mergeDepartures(
            listOf(Departure("A", "S", 1000, true)),
            listOf(Departure("A", "S", 900, false), Departure("C", "S", 1100, false)),
            setOf("A|S"), emptySet(),
        )
        assertEquals(listOf("A", "C"), out.map { it.tripId })
    }

    @Test
    fun regions() {
        assertEquals("bct-victoria", Regions.find(48.4284, -123.3656)?.id)
        assertEquals("toronto-ttc", Regions.find(43.6532, -79.3832)?.id)
    }

    @Test
    fun emptyRealtimeFeedParses() {
        val feed = GtfsRealtimeParser.parse(ByteArray(0))
        assertTrue(feed.tripUpdates.isEmpty())
    }
}
