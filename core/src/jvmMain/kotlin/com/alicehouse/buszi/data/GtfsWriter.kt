package com.alicehouse.buszi.data

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Imports a GTFS zip into an empty schema. stop_times.txt is streamed and compressed into
 * patterns, so even large feeds (Victoria: 92 MB of stop_times) end up a few MB.
 * The caller handles the transaction.
 */
class GtfsWriter(private val sql: SqlSink, private val log: (String) -> Unit = {}) {

    /**
     * [excludeRouteTypes]: GTFS route_type values left out on purpose, e.g. STM metro (1) whose
     * licence forbids building apps on its indicative metro schedules.
     */
    fun writeAll(
        zip: ZipFile,
        meta: Map<String, String>,
        excludeRouteTypes: Set<Int> = emptySet(),
        progress: (Float) -> Unit = {},
    ) {
        GtfsSchema.TABLES.forEach { sql.exec("DELETE FROM $it") }
        importAgency(zip)
        val excludedRoutes = importRoutes(zip, excludeRouteTypes)
        importStops(zip)
        val calendar = importCalendar(zip)
        val trips = readTrips(zip, calendar).apply { values.removeAll { it.routeId in excludedRoutes } }
        importStopTimes(zip, trips, progress)
        val sd = sql.prepare("INSERT OR IGNORE INTO service_dates VALUES(?, ?)")
        calendar.forEach { date, service -> sd.bind(1, date.toLong()); sd.bind(2, service.toLong()); sd.insert() }
        val m = sql.prepare("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)")
        fun put(k: String, v: String) { m.bind(1, k); m.bind(2, v); m.insert() }
        importFeedInfo(zip, ::put)
        put("schema", GtfsSchema.VERSION.toString())
        put("imported_at", System.currentTimeMillis().toString())
        meta.forEach { (k, v) -> put(k, v) }
        progress(1f)
    }

    private fun table(zip: ZipFile, name: String, wrap: (InputStream) -> InputStream = { it }): CsvTable? {
        val entry = zip.getEntry(name) ?: return null
        return CsvTable(InputStreamReader(wrap(zip.getInputStream(entry)), Charsets.UTF_8).buffered(64 * 1024))
    }

    private fun importAgency(zip: ZipFile) {
        val t = table(zip, "agency.txt") ?: return
        val id = t.col("agency_id"); val name = t.col("agency_name"); val url = t.col("agency_url"); val tz = t.col("agency_timezone")
        val st = sql.prepare("INSERT OR REPLACE INTO agency VALUES(?,?,?,?)")
        t.forEachRow { r -> st.bind(1, r.at(id)); st.bind(2, r.at(name)); st.bind(3, r.at(url)); st.bind(4, r.at(tz)); st.insert() }
    }

    /** Returns the ids of routes that were left out. */
    private fun importRoutes(zip: ZipFile, excludeTypes: Set<Int>): Set<String> {
        val t = table(zip, "routes.txt") ?: error("routes.txt missing")
        val id = t.col("route_id"); val ag = t.col("agency_id"); val sn = t.col("route_short_name")
        val ln = t.col("route_long_name"); val ty = t.col("route_type"); val co = t.col("route_color"); val tc = t.col("route_text_color")
        val st = sql.prepare("INSERT OR REPLACE INTO routes VALUES(?,?,?,?,?,?,?)")
        val excluded = HashSet<String>()
        t.forEachRow { r ->
            val type = r.at(ty).toIntOrNull() ?: 3
            if (type in excludeTypes) { excluded.add(r.at(id)); return@forEachRow }
            st.bind(1, r.at(id)); st.bind(2, r.at(ag)); st.bind(3, r.at(sn)); st.bind(4, r.at(ln))
            st.bind(5, type.toLong()); st.bind(6, r.at(co)); st.bind(7, r.at(tc))
            st.insert()
        }
        if (excluded.isNotEmpty()) log("Left out ${excluded.size} routes of types $excludeTypes")
        return excluded
    }

    private fun importStops(zip: ZipFile) {
        val t = table(zip, "stops.txt") ?: error("stops.txt missing")
        val id = t.col("stop_id"); val code = t.col("stop_code"); val name = t.col("stop_name")
        val lat = t.col("stop_lat"); val lon = t.col("stop_lon"); val lt = t.col("location_type"); val wc = t.col("wheelchair_boarding")
        val st = sql.prepare("INSERT OR REPLACE INTO stops VALUES(?,?,?,?,?,?)")
        t.forEachRow { r ->
            // Only boarding locations (location_type empty or 0).
            val type = r.at(lt)
            if (type.isNotEmpty() && type != "0") return@forEachRow
            val la = r.at(lat).toDoubleOrNull() ?: return@forEachRow
            val lo = r.at(lon).toDoubleOrNull() ?: return@forEachRow
            val stopId = r.at(id)
            st.bind(1, stopId); st.bind(2, r.at(code).ifEmpty { stopId }); st.bind(3, r.at(name))
            st.bind(4, la); st.bind(5, lo); st.bind(6, r.at(wc).toLongOrNull() ?: 0)
            st.insert()
        }
    }

    private fun importCalendar(zip: ZipFile): ServiceCalendar {
        val cal = ServiceCalendar()
        table(zip, "calendar.txt")?.let { t ->
            val sid = t.col("service_id"); val s = t.col("start_date"); val e = t.col("end_date")
            val dayCols = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").map { t.col(it) }
            t.forEachRow { r ->
                val start = ServiceTime.parseDate(r.at(s)) ?: return@forEachRow
                val end = ServiceTime.parseDate(r.at(e)) ?: return@forEachRow
                cal.addWeekly(r.at(sid), BooleanArray(7) { r.at(dayCols[it]) == "1" }, start, end)
            }
        }
        table(zip, "calendar_dates.txt")?.let { t ->
            val sid = t.col("service_id"); val d = t.col("date"); val ty = t.col("exception_type")
            t.forEachRow { r ->
                val date = r.at(d).toIntOrNull() ?: return@forEachRow
                cal.addException(r.at(sid), date, r.at(ty).toIntOrNull() ?: return@forEachRow)
            }
        }
        return cal
    }

    private class TripRow(val routeId: String, val directionId: Int, val headsign: String, val service: Int)

    /** trips.txt kept in memory only during the import; strings are shared to save RAM. */
    private fun readTrips(zip: ZipFile, calendar: ServiceCalendar): HashMap<String, TripRow> {
        val t = table(zip, "trips.txt") ?: error("trips.txt missing")
        val id = t.col("trip_id"); val rid = t.col("route_id"); val hs = t.col("trip_headsign")
        val dir = t.col("direction_id"); val sid = t.col("service_id")
        val pool = HashMap<String, String>()
        fun share(s: String) = pool.getOrPut(s) { s }
        val out = HashMap<String, TripRow>(100_000)
        t.forEachRow { r ->
            out[r.at(id)] = TripRow(share(r.at(rid)), r.at(dir).toIntOrNull() ?: 0, share(r.at(hs)), calendar.id(r.at(sid)))
        }
        return out
    }

    private fun importStopTimes(zip: ZipFile, trips: HashMap<String, TripRow>, progress: (Float) -> Unit) {
        val stopRef = HashMap<String, Int>()
        sql.pairs("SELECT stop_id, rowid FROM stops").forEach { (id, ref) -> stopRef[id] = ref }

        val insPattern = sql.prepare("INSERT INTO patterns VALUES(?,?,?,?,?)")
        val builder = PatternBuilder { p ->
            insPattern.bind(1, p.id.toLong()); insPattern.bind(2, p.routeId); insPattern.bind(3, p.directionId.toLong())
            insPattern.bind(4, p.headsign); insPattern.bind(5, p.stops); insPattern.insert()
        }
        val insTrip = sql.prepare("INSERT OR REPLACE INTO trips VALUES(?,?,?,?)")
        val done = HashSet<String>(trips.size * 2)
        fun saveTrip(tripId: String, rows: MutableList<StopTimeRow>) {
            if (tripId in done) return
            val trip = trips[tripId] ?: return
            val timing = builder.addTrip(trip.routeId, trip.directionId, trip.headsign, rows)
            insTrip.bind(1, timing.pattern.toLong()); insTrip.bind(2, timing.start.toLong())
            insTrip.bind(3, tripId); insTrip.bind(4, trip.service.toLong()); insTrip.insert()
            done.add(tripId)
        }

        val total = zip.getEntry("stop_times.txt")?.size?.takeIf { it > 0 } ?: 1L
        var read = 0L
        var lastReport = 0L
        val counting: (InputStream) -> InputStream = { input ->
            object : FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { n ->
                        if (n > 0) {
                            read += n
                            if (read - lastReport > 2_000_000) { lastReport = read; progress(read.toFloat() / total) }
                        }
                    }
            }
        }
        var skipped = 0
        table(zip, "stop_times.txt", counting)?.let { t ->
            val tid = t.col("trip_id"); val seq = t.col("stop_sequence"); val sid = t.col("stop_id")
            val dep = t.col("departure_time"); val arr = t.col("arrival_time"); val pu = t.col("pickup_type")
            var cur: String? = null
            val rows = ArrayList<StopTimeRow>(64)
            t.forEachRow { r ->
                val tripId = r.at(tid)
                if (tripId != cur) {
                    cur?.let { saveTrip(it, rows) }
                    rows.clear()
                    cur = tripId
                }
                // Feeds are grouped by trip in practice; rows of an already finished trip are ignored.
                if (tripId in done) { skipped++; return@forEachRow }
                val d = ServiceTime.parse(r.at(dep)).let { if (it >= 0) it else ServiceTime.parse(r.at(arr)) }
                rows.add(StopTimeRow(r.at(seq).toIntOrNull() ?: rows.size, stopRef[r.at(sid)] ?: -1, d, r.at(pu) == "1"))
            }
            cur?.let { saveTrip(it, rows) }
        }
        if (skipped > 0) log("stop_times.txt not grouped by trip: $skipped rows ignored")

        // Trips without stop times still need route/direction for real-time matching.
        for (tripId in trips.keys) if (tripId !in done) saveTrip(tripId, ArrayList())
        log("Imported ${trips.size} trips as ${builder.count} patterns")
    }

    private fun importFeedInfo(zip: ZipFile, put: (String, String) -> Unit) {
        val t = table(zip, "feed_info.txt") ?: return
        val ver = t.col("feed_version"); val end = t.col("feed_end_date")
        val row = t.nextRow() ?: return
        put("feed_version", row.at(ver))
        put("feed_end_date", row.at(end))
    }
}
