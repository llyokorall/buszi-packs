package com.alicehouse.buszi.data

/**
 * Database layout of one region. Shared by Android, the pack builder and (later) iOS:
 * a pack downloaded on iOS is the same SQLite file with this layout. See docs/PACK_FORMAT.md.
 */
object GtfsSchema {
    /** Bump when the layout changes: old files and packs are then rebuilt. */
    const val VERSION = 4

    /** Added in version 4: the road each pattern's buses take ([ShapeCodec]); empty when the feed has no shapes. */
    const val CREATE_SHAPES = "CREATE TABLE IF NOT EXISTS shapes(pattern INTEGER PRIMARY KEY, points BLOB)"

    val CREATE = listOf(
        "CREATE TABLE agency(agency_id TEXT PRIMARY KEY, name TEXT, url TEXT, tz TEXT)",
        "CREATE TABLE routes(route_id TEXT PRIMARY KEY, agency_id TEXT, short_name TEXT, long_name TEXT, " +
            "type INTEGER, color TEXT, text_color TEXT)",
        "CREATE TABLE stops(stop_id TEXT PRIMARY KEY, code TEXT, name TEXT, lat REAL, lon REAL, wheelchair INTEGER)",
        "CREATE INDEX stops_lat ON stops(lat)",
        // Compact timetable (see Schedule.kt): one row per pattern, stops packed in a blob.
        "CREATE TABLE patterns(pattern INTEGER PRIMARY KEY, route_id TEXT, direction_id INTEGER, headsign TEXT, stops BLOB)",
        // Trips clustered by pattern + start time; second index only for real-time trip_id lookups.
        "CREATE TABLE trips(pattern INTEGER, start_sec INTEGER, trip_id TEXT, service INTEGER, " +
            "PRIMARY KEY(pattern, start_sec, trip_id)) WITHOUT ROWID",
        "CREATE INDEX trips_id ON trips(trip_id)",
        "CREATE TABLE service_dates(date INTEGER, service INTEGER, PRIMARY KEY(date, service)) WITHOUT ROWID",
        "CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)",
        CREATE_SHAPES,
    )

    val TABLES = listOf("agency", "routes", "stops", "patterns", "trips", "service_dates", "meta", "shapes")
}

/** Minimal SQL access so the same import code can run on Android SQLite, JDBC and iOS SQLite. */
interface SqlSink {
    fun exec(sql: String)
    fun prepare(sql: String): SqlStatement
    /** Rows of a two-column query (TEXT, INTEGER). */
    fun pairs(sql: String): List<Pair<String, Int>>
}

interface SqlStatement {
    fun bind(index: Int, value: String)
    fun bind(index: Int, value: Long)
    fun bind(index: Int, value: Double)
    fun bind(index: Int, value: ByteArray)
    fun insert()
}
