@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.alicehouse.buszi.data

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/*
 * Compact static timetable.
 *
 * A GTFS feed repeats the same stop list and running times for thousands of trips
 * (Edmonton: 2.9 M stop_times rows, 88 k trips). Trips that share route, direction,
 * headsign, stops and relative times are stored once as a *pattern*; each trip is then
 * just (pattern, start time, service). For Edmonton that is ~4.3 k patterns and the whole
 * timetable fits in a few MB instead of ~165 MB.
 *
 * Everything in this file is Kotlin Multiplatform common code: it runs on Android, the JVM
 * pack builder and iOS (no java.* APIs here).
 */

/** One upcoming departure of one trip at one stop. */
data class Departure(
    val tripId: String,
    val stopId: String,
    val epochSec: Long,
    val realtime: Boolean,
)

/** GTFS time helpers. Times are seconds after the start of the "service day" and may pass 24:00. */
object ServiceTime {
    /** "25:10:00" -> 90600. Returns -1 for an empty or malformed value. */
    fun parse(t: String): Int {
        val s = t.trim()
        if (s.isEmpty()) return -1
        val a = s.indexOf(':')
        val b = s.indexOf(':', a + 1)
        if (a <= 0 || b <= a) return -1
        val h = s.substring(0, a).toIntOrNull() ?: return -1
        val m = s.substring(a + 1, b).toIntOrNull() ?: return -1
        val sec = s.substring(b + 1).toIntOrNull() ?: return -1
        return h * 3600 + m * 60 + sec
    }

    /** GTFS reference point: "noon minus 12 h" local time (correct on DST change days). */
    fun dayStart(date: LocalDate, zone: TimeZone): Long =
        LocalDateTime(date, LocalTime(12, 0)).toInstant(zone).epochSeconds - 12 * 3600

    fun dateInt(d: LocalDate): Int = d.year * 10000 + (d.month.ordinal + 1) * 100 + d.day

    /** "20261111" -> LocalDate, or null. */
    fun parseDate(s: String): LocalDate? {
        val v = s.trim()
        if (v.length != 8) return null
        return runCatching { LocalDate(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt()) }.getOrNull()
    }
}

/** Builds the set of dates each service runs, from calendar.txt + calendar_dates.txt. */
class ServiceCalendar {
    private val ids = HashMap<String, Int>()
    private val dates = HashMap<Int, MutableSet<Int>>()

    /** Small integer id for a GTFS service_id (stable for one import). */
    fun id(serviceId: String): Int = ids.getOrPut(serviceId) { ids.size }

    /** calendar.txt row. [days] is Monday..Sunday. Call before [addException]. */
    fun addWeekly(serviceId: String, days: BooleanArray, start: LocalDate, end: LocalDate) {
        val id = id(serviceId)
        var d = start
        var n = 0
        while (d <= end && n < MAX_DAYS) {
            if (days[d.dayOfWeek.isoDayNumber - 1]) dates.getOrPut(id) { HashSet() }.add(ServiceTime.dateInt(d))
            d = d.plus(1, DateTimeUnit.DAY)
            n++
        }
    }

    /** calendar_dates.txt row: type 1 = added, 2 = removed. */
    fun addException(serviceId: String, date: Int, type: Int) {
        val id = id(serviceId)
        when (type) {
            1 -> dates.getOrPut(id) { HashSet() }.add(date)
            2 -> dates[id]?.remove(date)
        }
    }

    fun forEach(block: (date: Int, service: Int) -> Unit) {
        for ((service, ds) in dates) for (d in ds) block(d, service)
    }

    companion object {
        /** Guards against feeds with absurd date ranges. */
        const val MAX_DAYS = 400
    }
}

/**
 * Packs a pattern's stops as varints: (stopRef, (offsetDelta shl 1) or canBoard).
 * About 4 bytes per stop.
 */
object PatternCodec {
    fun encode(stopRefs: IntArray, offsets: IntArray, board: BooleanArray): ByteArray {
        val out = ByteBuffer(stopRefs.size * 4 + 8)
        var prev = 0
        for (i in stopRefs.indices) {
            writeVarint(out, stopRefs[i])
            val delta = (offsets[i] - prev).coerceAtLeast(0)
            writeVarint(out, (delta shl 1) or (if (board[i]) 1 else 0))
            prev += delta
        }
        return out.toByteArray()
    }

    /** Tiny growable byte buffer (common code has no ByteArrayOutputStream). */
    private class ByteBuffer(capacity: Int) {
        private var buf = ByteArray(capacity)
        private var size = 0
        fun write(b: Int) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = b.toByte()
        }
        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    inline fun decode(blob: ByteArray, onStop: (stopRef: Int, offset: Int, canBoard: Boolean) -> Unit) {
        var pos = 0
        var offset = 0
        while (pos < blob.size) {
            var ref = 0; var shift = 0
            while (true) { val b = blob[pos++].toInt(); ref = ref or ((b and 0x7F) shl shift); if (b and 0x80 == 0) break; shift += 7 }
            var v = 0; shift = 0
            while (true) { val b = blob[pos++].toInt(); v = v or ((b and 0x7F) shl shift); if (b and 0x80 == 0) break; shift += 7 }
            offset += v ushr 1
            onStop(ref, offset, v and 1 == 1)
        }
    }

    private fun writeVarint(out: ByteBuffer, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v)
    }
}

/** One stop_times row, already mapped: [stopRef] is the stop's row id (-1 if unknown). */
class StopTimeRow(val seq: Int, val stopRef: Int, val dep: Int, val noPickup: Boolean)

/** Deduplicates trips into patterns. */
class PatternBuilder(private val onNewPattern: (Pattern) -> Unit) {
    class Pattern(val id: Int, val routeId: String, val directionId: Int, val headsign: String, val stops: ByteArray)
    class TripTiming(val pattern: Int, val start: Int)

    private val ids = HashMap<String, Int>()
    val count: Int get() = ids.size

    fun addTrip(routeId: String, directionId: Int, headsign: String, rows: MutableList<StopTimeRow>): TripTiming {
        rows.sortBy { it.seq }
        val n = rows.size
        val times = IntArray(n) { rows[it].dep }
        interpolate(times)
        val start = if (n > 0 && times[0] >= 0) times[0] else 0

        val keep = (0 until n).filter { rows[it].stopRef >= 0 }
        val refs = IntArray(keep.size) { rows[keep[it]].stopRef }
        val offs = IntArray(keep.size)
        var prev = 0
        for (i in keep.indices) {
            val t = times[keep[i]]
            prev = maxOf(prev, if (t >= 0) t - start else prev)
            offs[i] = prev
        }
        // No boarding at the last stop of a trip, nor where the feed says so.
        val board = BooleanArray(keep.size) { i -> !rows[keep[i]].noPickup && keep[i] != n - 1 }
        val blob = PatternCodec.encode(refs, offs, board)

        val key = buildString(blob.size + routeId.length + headsign.length + 8) {
            append(routeId).append('\u0001').append(directionId).append('\u0001').append(headsign).append('\u0001')
            for (byte in blob) append((byte.toInt() and 0xFF).toChar())
        }
        val id = ids.getOrPut(key) {
            val newId = ids.size
            onNewPattern(Pattern(newId, routeId, directionId, headsign, blob))
            newId
        }
        return TripTiming(id, start)
    }

    /** Fills missing (-1) times linearly between known ones. */
    private fun interpolate(t: IntArray) {
        val known = t.indices.filter { t[it] >= 0 }
        if (known.isEmpty()) return
        for (i in 0 until known.first()) t[i] = t[known.first()]
        for (i in known.last() + 1 until t.size) t[i] = t[known.last()]
        for (k in 0 until known.size - 1) {
            val a = known[k]; val b = known[k + 1]
            for (i in a + 1 until b) t[i] = t[a] + (t[b] - t[a]) * (i - a) / (b - a)
        }
    }
}

/**
 * Combines real-time and scheduled departures at one stop.
 * A scheduled departure is dropped when real-time data covers that trip at that stop
 * ([liveKeys] = "tripId|stopId", including past and skipped stops) or the trip is canceled.
 */
fun mergeDepartures(
    live: List<Departure>,
    scheduled: List<Departure>,
    liveKeys: Set<String>,
    canceled: Set<String>,
): List<Departure> {
    if (scheduled.isEmpty()) return live
    val out = ArrayList<Departure>(live.size + scheduled.size)
    out.addAll(live)
    for (d in scheduled) {
        if (d.tripId in canceled || "${d.tripId}|${d.stopId}" in liveKeys) continue
        out.add(d)
    }
    out.sortBy { it.epochSec }
    return out
}
