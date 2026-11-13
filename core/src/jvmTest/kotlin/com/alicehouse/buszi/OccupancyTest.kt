package com.alicehouse.buszi

import com.alicehouse.buszi.data.Crowding
import com.alicehouse.buszi.data.GtfsRealtimeParser
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class OccupancyTest {
    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (x >= 0x80) { write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        write(x.toInt())
    }
    private fun ByteArrayOutputStream.msg(field: Int, body: ByteArray) { varint((field shl 3 or 2).toLong()); varint(body.size.toLong()); write(body) }
    private fun ByteArrayOutputStream.float(field: Int, f: Float) {
        varint((field shl 3 or 5).toLong())
        val b = java.lang.Float.floatToIntBits(f)
        repeat(4) { write((b ushr (8 * it)) and 0xFF) }
    }
    private fun bytes(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()

    @Test
    fun readsOccupancyStatus() {
        val trip = bytes { msg(1, "T1".toByteArray()) }
        val pos = bytes { float(1, 49.1f); float(2, -123.9f) }
        val vehicle = bytes { msg(1, trip); msg(2, pos); varint((9 shl 3).toLong()); varint(2) }
        val feed = bytes { msg(2, bytes { msg(1, "e1".toByteArray()); msg(4, vehicle) }) }
        val v = GtfsRealtimeParser.parse(feed).vehicles.single()
        assertEquals("T1", v.tripId)
        assertEquals(2, v.occupancy)
        assertEquals(2, Crowding.level(v.occupancy))
    }

    @Test
    fun levels() {
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 3, 0, 0, 0), listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, -1).map(Crowding::level))
    }

    @Test
    fun feedWithOneValueForEveryBusIsNotTrusted() {
        // Halifax-style: every bus "many seats" = a default, not a passenger count.
        assertEquals(listOf(0, 0, 0), Crowding.levels(listOf(1, 1, -1)))
        // EMPTY everywhere (Seattle-style default) never counts.
        assertEquals(false, Crowding.trusted(listOf(0, 0, 0, 1)))
        // Real data: several levels.
        assertEquals(listOf(1, 2, 0, 3), Crowding.levels(listOf(1, 2, 7, 5)))
    }
}
