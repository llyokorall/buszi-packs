package com.alicehouse.buszi.data

/**
 * Minimal GTFS-Realtime decoder.
 *
 * Reads the protobuf wire format directly using the public field numbers from the
 * GTFS-Realtime specification (https://gtfs.org/realtime/reference/), so we do not
 * need code generation or the protobuf runtime. Only fields the app uses are read;
 * everything else is skipped.
 */

data class RtStopTime(
    val stopId: String,
    val stopSequence: Int,
    /** epoch seconds; 0 when unknown */
    val arrival: Long,
    val departure: Long,
    val skipped: Boolean,
) {
    val bestTime: Long get() = if (departure > 0) departure else arrival
}

data class RtTripUpdate(
    val tripId: String,
    val routeId: String,
    val canceled: Boolean,
    val stopTimes: List<RtStopTime>,
)

data class RtVehicle(
    val tripId: String,
    val routeId: String,
    val lat: Float,
    val lon: Float,
    val bearing: Float,
    val label: String,
)

data class RtAlert(
    val id: String,
    val header: String,
    val description: String,
    val url: String,
    val routeIds: Set<String>,
    val stopIds: Set<String>,
    val periods: List<LongRange>,
) {
    fun isActive(nowSec: Long): Boolean =
        periods.isEmpty() || periods.any { nowSec in it }
}

data class RtFeed(
    val timestamp: Long,
    val tripUpdates: List<RtTripUpdate> = emptyList(),
    val vehicles: List<RtVehicle> = emptyList(),
    val alerts: List<RtAlert> = emptyList(),
)

private class Pb(private val b: ByteArray, var pos: Int, private val end: Int) {
    fun more() = pos < end

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val x = b[pos++].toInt() and 0xFF
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
        }
        error("bad varint")
    }

    fun tag(): Int = varint().toInt()
    fun len(): Int = varint().toInt()

    fun sub(): Pb {
        val n = len()
        val r = Pb(b, pos, pos + n)
        pos += n
        return r
    }

    fun string(): String {
        val n = len()
        val s = b.decodeToString(pos, pos + n)
        pos += n
        return s
    }

    fun fixed32(): Int {
        val v = (b[pos].toInt() and 0xFF) or
            ((b[pos + 1].toInt() and 0xFF) shl 8) or
            ((b[pos + 2].toInt() and 0xFF) shl 16) or
            ((b[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun float(): Float = Float.fromBits(fixed32())

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val n = len(); pos += n } // read length first: `pos += len()` would use the old pos
            5 -> pos += 4
            else -> error("unsupported wire type $wire")
        }
    }
}

object GtfsRealtimeParser {

    fun parse(bytes: ByteArray): RtFeed {
        val p = Pb(bytes, 0, bytes.size)
        var timestamp = 0L
        val trips = ArrayList<RtTripUpdate>()
        val vehicles = ArrayList<RtVehicle>()
        val alerts = ArrayList<RtAlert>()
        while (p.more()) {
            val t = p.tag()
            when (t ushr 3) {
                1 -> if (t and 7 == 2) timestamp = header(p.sub()) else p.skip(t and 7)
                2 -> if (t and 7 == 2) entity(p.sub(), trips, vehicles, alerts) else p.skip(t and 7)
                else -> p.skip(t and 7)
            }
        }
        return RtFeed(timestamp, trips, vehicles, alerts)
    }

    private fun header(p: Pb): Long {
        var ts = 0L
        while (p.more()) {
            val t = p.tag()
            if (t ushr 3 == 3 && t and 7 == 0) ts = p.varint() else p.skip(t and 7)
        }
        return ts
    }

    private fun entity(p: Pb, trips: MutableList<RtTripUpdate>, vehicles: MutableList<RtVehicle>, alerts: MutableList<RtAlert>) {
        var id = ""
        var deleted = false
        var tu: RtTripUpdate? = null
        var vp: RtVehicle? = null
        var al: RtAlert? = null
        while (p.more()) {
            val t = p.tag()
            val wire = t and 7
            when (t ushr 3) {
                1 -> if (wire == 2) id = p.string() else p.skip(wire)
                2 -> if (wire == 0) deleted = p.varint() != 0L else p.skip(wire)
                3 -> if (wire == 2) tu = tripUpdate(p.sub()) else p.skip(wire)
                4 -> if (wire == 2) vp = vehicle(p.sub()) else p.skip(wire)
                5 -> if (wire == 2) al = alert(p.sub(), id) else p.skip(wire)
                else -> p.skip(wire)
            }
        }
        if (deleted) return
        tu?.let(trips::add)
        vp?.let(vehicles::add)
        al?.let { alerts.add(it.copy(id = id)) }
    }

    private class TripDesc(val tripId: String, val routeId: String, val scheduleRelationship: Int)

    private fun tripDescriptor(p: Pb): TripDesc {
        var tripId = ""
        var routeId = ""
        var rel = 0
        while (p.more()) {
            val t = p.tag()
            val wire = t and 7
            when (t ushr 3) {
                1 -> if (wire == 2) tripId = p.string() else p.skip(wire)
                5 -> if (wire == 2) routeId = p.string() else p.skip(wire)
                4 -> if (wire == 0) rel = p.varint().toInt() else p.skip(wire)
                else -> p.skip(wire)
            }
        }
        return TripDesc(tripId, routeId, rel)
    }

    private fun tripUpdate(p: Pb): RtTripUpdate {
        var desc = TripDesc("", "", 0)
        val stops = ArrayList<RtStopTime>()
        while (p.more()) {
            val t = p.tag()
            val wire = t and 7
            when (t ushr 3) {
                1 -> if (wire == 2) desc = tripDescriptor(p.sub()) else p.skip(wire)
                2 -> if (wire == 2) stops.add(stopTimeUpdate(p.sub())) else p.skip(wire)
                else -> p.skip(wire)
            }
        }
        // TripDescriptor.ScheduleRelationship.CANCELED = 3
        return RtTripUpdate(desc.tripId, desc.routeId, desc.scheduleRelationship == 3, stops)
    }

    private fun stopTimeUpdate(p: Pb): RtStopTime {
        var seq = -1
        var stopId = ""
        var arr = 0L
        var dep = 0L
        var rel = 0
        while (p.more()) {
            val t = p.tag()
            val wire = t and 7
            when (t ushr 3) {
                1 -> if (wire == 0) seq = p.varint().toInt() else p.skip(wire)
                2 -> if (wire == 2) arr = eventTime(p.sub()) else p.skip(wire)
                3 -> if (wire == 2) dep = eventTime(p.sub()) else p.skip(wire)
                4 -> if (wire == 2) stopId = p.string() else p.skip(wire)
                5 -> if (wire == 0) rel = p.varint().toInt() else p.skip(wire)
                else -> p.skip(wire)
            }
        }
        // StopTimeUpdate.ScheduleRelationship.SKIPPED = 1, NO_DATA = 2
        return RtStopTime(stopId, seq, arr, dep, skipped = rel == 1 || rel == 2)
    }

    private fun eventTime(p: Pb): Long {
        var time = 0L
        while (p.more()) {
            val t = p.tag()
            if (t ushr 3 == 2 && t and 7 == 0) time = p.varint() else p.skip(t and 7)
        }
        return time
    }

    private fun vehicle(p: Pb): RtVehicle? {
        var desc = TripDesc("", "", 0)
        var lat = Float.NaN
        var lon = Float.NaN
        var bearing = Float.NaN
        var label = ""
        while (p.more()) {
            val t = p.tag()
            val wire = t and 7
            when (t ushr 3) {
                1 -> if (wire == 2) desc = tripDescriptor(p.sub()) else p.skip(wire)
                2 -> if (wire == 2) {
                    val pos = p.sub()
                    while (pos.more()) {
                        val pt = pos.tag()
                        when {
                            pt ushr 3 == 1 && pt and 7 == 5 -> lat = pos.float()
                            pt ushr 3 == 2 && pt and 7 == 5 -> lon = pos.float()
                            pt ushr 3 == 3 && pt and 7 == 5 -> bearing = pos.float()
                            else -> pos.skip(pt and 7)
                        }
                    }
                } else p.skip(wire)
                8 -> if (wire == 2) {
                    val vd = p.sub()
                    while (vd.more()) {
                        val vt = vd.tag()
                        if (vt ushr 3 == 2 && vt and 7 == 2) label = vd.string() else vd.skip(vt and 7)
                    }
                } else p.skip(wire)
                else -> p.skip(wire)
            }
        }
        if (lat.isNaN() || lon.isNaN()) return null
        return RtVehicle(desc.tripId, desc.routeId, lat, lon, bearing, label)
    }

    private fun alert(p: Pb, id: String): RtAlert {
        val periods = ArrayList<LongRange>()
        val routes = HashSet<String>()
        val stops = HashSet<String>()
        var header = ""
        var desc = ""
        var url = ""
        while (p.more()) {
            val t = p.tag()
            val wire = t and 7
            when (t ushr 3) {
                1 -> if (wire == 2) {
                    val r = p.sub()
                    var start = 0L
                    var end = Long.MAX_VALUE
                    while (r.more()) {
                        val rt = r.tag()
                        when {
                            rt ushr 3 == 1 && rt and 7 == 0 -> start = r.varint()
                            rt ushr 3 == 2 && rt and 7 == 0 -> end = r.varint().let { if (it == 0L) Long.MAX_VALUE else it }
                            else -> r.skip(rt and 7)
                        }
                    }
                    periods.add(start..end)
                } else p.skip(wire)
                5 -> if (wire == 2) {
                    val e = p.sub()
                    while (e.more()) {
                        val et = e.tag()
                        when {
                            et ushr 3 == 2 && et and 7 == 2 -> routes.add(e.string())
                            et ushr 3 == 5 && et and 7 == 2 -> stops.add(e.string())
                            et ushr 3 == 4 && et and 7 == 2 -> tripDescriptor(e.sub()).routeId.takeIf { it.isNotEmpty() }?.let(routes::add)
                            else -> e.skip(et and 7)
                        }
                    }
                } else p.skip(wire)
                8 -> if (wire == 2) url = translated(p.sub()) else p.skip(wire)
                10 -> if (wire == 2) header = translated(p.sub()) else p.skip(wire)
                11 -> if (wire == 2) desc = translated(p.sub()) else p.skip(wire)
                else -> p.skip(wire)
            }
        }
        return RtAlert(id, header, desc, url, routes, stops, periods)
    }

    /** Picks English if present, else the first translation. */
    private fun translated(p: Pb): String {
        var first: String? = null
        var english: String? = null
        while (p.more()) {
            val t = p.tag()
            if (t ushr 3 == 1 && t and 7 == 2) {
                val tr = p.sub()
                var text = ""
                var lang = ""
                while (tr.more()) {
                    val tt = tr.tag()
                    when {
                        tt ushr 3 == 1 && tt and 7 == 2 -> text = tr.string()
                        tt ushr 3 == 2 && tt and 7 == 2 -> lang = tr.string()
                        else -> tr.skip(tt and 7)
                    }
                }
                if (first == null) first = text
                if (lang.startsWith("en", ignoreCase = true) && english == null) english = text
            } else p.skip(t and 7)
        }
        return english ?: first ?: ""
    }
}
