package com.alicehouse.buszi.data

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * Street-following paths for the trip planner, computed on the phone from the vector map tiles
 * the app already shows (OpenFreeMap / OpenMapTiles schema, data © OpenStreetMap contributors).
 * Only tiles are downloaded; no route request with the user's position goes to any server.
 */

/**
 * One street line from a tile, in lat/lon. [cls] is the OpenMapTiles "class" (minor, path, primary…),
 * [sub] the "subclass" (cycleway, footway, steps…) and [bike] the "bicycle" access (yes, designated, no…).
 */
class StreetLine(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cls: String,
    val oneway: Int,
    val sub: String = "",
    val bike: String = "",
) {
    /** A path meant for bikes: a cycleway, or any way marked bicycle=designated. */
    val isBikeway: Boolean get() = sub == "cycleway" || bike == "designated"
}

object Tiles {
    const val ZOOM = 14

    fun x(lon: Double, z: Int = ZOOM): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    fun y(lat: Double, z: Int = ZOOM): Int {
        val r = lat * PI / 180
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt()
    }

    /** Tiles covering a box around two points, with [marginM] metres to spare. */
    fun around(lat1: Double, lon1: Double, lat2: Double, lon2: Double, marginM: Double): Set<Pair<Int, Int>> {
        val dLat = marginM / 111_000.0
        val dLon = marginM / (111_000.0 * cos(lat1 * PI / 180))
        val x0 = x(minOf(lon1, lon2) - dLon); val x1 = x(maxOf(lon1, lon2) + dLon)
        val y0 = y(maxOf(lat1, lat2) + dLat); val y1 = y(minOf(lat1, lat2) - dLat)
        val out = HashSet<Pair<Int, Int>>()
        for (tx in x0..x1) for (ty in y0..y1) out.add(tx to ty)
        return out
    }
}

/** A named point from a tile: a shop/place ([isPoi]) or a house number. */
class NamedPoint(val lat: Double, val lon: Double, val name: String, val isPoi: Boolean)

/** A named street line from a tile; [cls] is the OpenMapTiles class (minor, primary, path…). */
class NamedLine(val lat: DoubleArray, val lon: DoubleArray, val name: String, val cls: String = "")

private val STREET_TYPES = mapOf(
    "Street" to "St", "Avenue" to "Ave", "Road" to "Rd", "Drive" to "Dr", "Boulevard" to "Blvd",
    "Crescent" to "Cres", "Place" to "Pl", "Court" to "Ct", "Lane" to "Ln", "Highway" to "Hwy",
    "Terrace" to "Terr", "Parkway" to "Pkwy", "Square" to "Sq", "Circle" to "Cir", "Trail" to "Trl",
)
private val DIRECTIONS = mapOf(
    "North" to "N", "South" to "S", "East" to "E", "West" to "W",
    "Northeast" to "NE", "Northwest" to "NW", "Southeast" to "SE", "Southwest" to "SW",
)

/**
 * Short street name for map labels, the usual North American way: "Terminal Avenue North" →
 * "Terminal Ave N". The first word is never shortened ("Avenue Road", "North Road" stay readable).
 */
fun shortStreetName(name: String): String {
    val w = name.split(' ').filter { it.isNotEmpty() }.toMutableList()
    if (w.size < 2) return name
    for (i in 1 until w.size) STREET_TYPES[w[i]]?.let { w[i] = it }
    if (w.size >= 3) DIRECTIONS[w.last()]?.let { w[w.size - 1] = it }
    return w.joinToString(" ")
}

/** Streets, building outlines (rings as lat,lon,lat,lon…) and address labels of one tile. */
class StreetTile(
    val lines: List<StreetLine>,
    val buildings: List<DoubleArray>,
    val points: List<NamedPoint> = emptyList(),
    val names: List<NamedLine> = emptyList(),
)

/**
 * "What's here?" for a map point, from tile labels only (on the phone): the nearest shop or place,
 * else the house number + street, else the street name. Returns (title, detail) or null.
 */
fun describePoint(tiles: List<StreetTile>, lat: Double, lon: Double): Pair<String, String?>? {
    fun nearestStreet(la: Double, lo: Double, maxM: Double): String? {
        var best: String? = null; var bestD = maxM
        for (t in tiles) for (l in t.names) {
            for (i in 0 until l.lat.size - 1) {
                val d = pointSegmentMeters(la, lo, l.lat[i], l.lon[i], l.lat[i + 1], l.lon[i + 1])
                if (d < bestD) { bestD = d; best = l.name }
            }
        }
        return best
    }
    var poi: NamedPoint? = null; var poiD = 35.0
    var num: NamedPoint? = null; var numD = 45.0
    for (t in tiles) for (p in t.points) {
        val d = Walking.meters(lat, lon, p.lat, p.lon)
        if (p.isPoi && d < poiD) { poiD = d; poi = p }
        if (!p.isPoi && d < numD) { numD = d; num = p }
    }
    val address = num?.let { n -> nearestStreet(n.lat, n.lon, 60.0)?.let { "${n.name} $it" } }
    return when {
        poi != null -> poi.name to (address ?: nearestStreet(lat, lon, 80.0))
        address != null -> address to null
        else -> nearestStreet(lat, lon, 80.0)?.let { it to null }
    }
}

private fun pointSegmentMeters(la: Double, lo: Double, la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val k = cos(la * PI / 180)
    val ax = lo1 * k; val ay = la1; val bx = lo2 * k; val by = la2; val px = lo * k
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (la - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return Walking.meters(la, lo, la1 + t * (la2 - la1), lo1 + t * (lo2 - lo1))
}

/** Reads the "transportation" and "building" layers of a Mapbox Vector Tile. */
object MvtStreets {
    fun decode(bytes: ByteArray, z: Int, tx: Int, ty: Int): List<StreetLine> = decodeTile(bytes, z, tx, ty).lines

    fun decodeTile(bytes: ByteArray, z: Int, tx: Int, ty: Int): StreetTile {
        val out = ArrayList<StreetLine>()
        val buildings = ArrayList<DoubleArray>()
        val points = ArrayList<NamedPoint>()
        val names = ArrayList<NamedLine>()
        val p = Pb(bytes, 0, bytes.size)
        while (p.more()) {
            val tag = p.tag()
            if (tag ushr 3 == 3 && tag and 7 == 2) layer(p.sub(), z, tx, ty, out, buildings, points, names) else p.skip(tag and 7)
        }
        return StreetTile(out, buildings, points, names)
    }

    private fun layer(
        p: Pb, z: Int, tx: Int, ty: Int,
        out: MutableList<StreetLine>, buildings: MutableList<DoubleArray>,
        points: MutableList<NamedPoint>, names: MutableList<NamedLine>,
    ) {
        var name = ""
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val features = ArrayList<Pb>()
        var extent = 4096
        while (p.more()) {
            val tag = p.tag()
            when (tag ushr 3) {
                1 -> name = p.string()
                2 -> features.add(p.sub())
                3 -> keys.add(p.string())
                4 -> values.add(value(p.sub()))
                5 -> extent = p.varint().toInt()
                else -> p.skip(tag and 7)
            }
        }
        if (name == "building") { buildingLayer(features, z, tx, ty, extent, buildings); return }
        if (name == "poi" || name == "housenumber" || name == "transportation_name") {
            labelLayer(name, features, keys, values, z, tx, ty, extent, points, names); return
        }
        if (name != "transportation") return
        val classKey = keys.indexOf("class")
        val onewayKey = keys.indexOf("oneway")
        val subKey = keys.indexOf("subclass")
        val bikeKey = keys.indexOf("bicycle")
        val n = 1 shl z
        for (f in features) {
            var type = 0
            var tags = IntArray(0)
            var geom = IntArray(0)
            while (f.more()) {
                val tag = f.tag()
                when (tag ushr 3) {
                    2 -> tags = packed(f.sub())
                    3 -> type = f.varint().toInt()
                    4 -> geom = packed(f.sub())
                    else -> f.skip(tag and 7)
                }
            }
            if (type != 2) continue
            var cls = ""; var oneway = 0; var sub = ""; var bike = ""
            var i = 0
            while (i + 1 < tags.size) {
                val k = tags[i]; val v = values.getOrNull(tags[i + 1]) ?: ""
                if (k == classKey) cls = v
                if (k == onewayKey) oneway = v.toIntOrNull() ?: 0
                if (k == subKey) sub = v
                if (k == bikeKey) bike = v
                i += 2
            }
            // geometry: MoveTo / LineTo commands with zig-zag deltas (tile units)
            var cx = 0; var cy = 0
            var xs = ArrayList<Double>(); var ys = ArrayList<Double>()
            var g = 0
            fun flush() {
                if (xs.size >= 2) clipToTile(xs, ys, extent.toDouble()) { px, py ->
                    val lat = DoubleArray(px.size) { tileLat(ty, py[it], extent, n) }
                    val lon = DoubleArray(px.size) { ((tx + px[it] / extent) / n) * 360.0 - 180.0 }
                    out.add(StreetLine(lat, lon, cls, oneway, sub, bike))
                }
                xs = ArrayList(); ys = ArrayList()
            }
            while (g < geom.size) {
                val cmd = geom[g] and 7; val count = geom[g] ushr 3; g++
                if (cmd == 1) flush()
                if (cmd == 1 || cmd == 2) {
                    repeat(count) {
                        if (g + 1 >= geom.size) return@repeat
                        cx += zigzag(geom[g]); cy += zigzag(geom[g + 1]); g += 2
                        xs.add(cx.toDouble()); ys.add(cy.toDouble())
                    }
                }
            }
            flush()
        }
    }

    private fun labelLayer(
        layer: String, features: List<Pb>, keys: List<String>, values: List<String>,
        z: Int, tx: Int, ty: Int, extent: Int,
        points: MutableList<NamedPoint>, names: MutableList<NamedLine>,
    ) {
        val n = 1 shl z
        val want = if (layer == "housenumber") "housenumber" else "name"
        val key = keys.indexOf(want)
        if (key < 0) return
        val classKey = keys.indexOf("class")
        for (f in features) {
            var type = 0
            var tags = IntArray(0)
            var geom = IntArray(0)
            while (f.more()) {
                val tag = f.tag()
                when (tag ushr 3) {
                    2 -> tags = packed(f.sub())
                    3 -> type = f.varint().toInt()
                    4 -> geom = packed(f.sub())
                    else -> f.skip(tag and 7)
                }
            }
            var label = ""
            var cls = ""
            var i = 0
            while (i + 1 < tags.size) {
                if (tags[i] == key) label = values.getOrNull(tags[i + 1]) ?: ""
                if (tags[i] == classKey) cls = values.getOrNull(tags[i + 1]) ?: ""
                i += 2
            }
            if (label.isBlank()) continue
            var cx = 0; var cy = 0; var g = 0
            var la = ArrayList<Double>(); var lo = ArrayList<Double>()
            while (g < geom.size) {
                val cmd = geom[g] and 7; val count = geom[g] ushr 3; g++
                // A new MoveTo starts a separate piece of a street: don't join the pieces.
                if (cmd == 1 && type == 2 && la.isNotEmpty()) {
                    if (la.size >= 2) names.add(NamedLine(la.toDoubleArray(), lo.toDoubleArray(), label, cls))
                    la = ArrayList(); lo = ArrayList()
                }
                if (cmd == 1 || cmd == 2) repeat(count) {
                    if (g + 1 >= geom.size) return@repeat
                    cx += zigzag(geom[g]); cy += zigzag(geom[g + 1]); g += 2
                    if (cx < 0 || cy < 0 || cx > extent || cy > extent) { if (type == 1) return@repeat }
                    la.add(tileLat(ty, cy.toDouble(), extent, n))
                    lo.add(((tx + cx.toDouble() / extent) / n) * 360.0 - 180.0)
                }
            }
            if (la.isEmpty()) continue
            if (type == 1) la.indices.forEach { points.add(NamedPoint(la[it], lo[it], label, layer == "poi")) }
            else if (type == 2 && la.size >= 2) names.add(NamedLine(la.toDoubleArray(), lo.toDoubleArray(), label, cls))
        }
    }

    private fun buildingLayer(features: List<Pb>, z: Int, tx: Int, ty: Int, extent: Int, out: MutableList<DoubleArray>) {
        val n = 1 shl z
        for (f in features) {
            var type = 0
            var geom = IntArray(0)
            while (f.more()) {
                val tag = f.tag()
                when (tag ushr 3) {
                    3 -> type = f.varint().toInt()
                    4 -> geom = packed(f.sub())
                    else -> f.skip(tag and 7)
                }
            }
            if (type != 3) continue
            var cx = 0; var cy = 0; var g = 0
            var ring = ArrayList<Double>()
            fun close() {
                if (ring.size >= 6) out.add(ring.toDoubleArray())
                ring = ArrayList()
            }
            while (g < geom.size) {
                val cmd = geom[g] and 7; val count = geom[g] ushr 3; g++
                when (cmd) {
                    1, 2 -> {
                        if (cmd == 1) close()
                        repeat(count) {
                            if (g + 1 >= geom.size) return@repeat
                            cx += zigzag(geom[g]); cy += zigzag(geom[g + 1]); g += 2
                            ring.add(tileLat(ty, cy.toDouble(), extent, n))
                            ring.add(((tx + cx.toDouble() / extent) / n) * 360.0 - 180.0)
                        }
                    }
                    7 -> close()
                }
            }
            close()
        }
    }

    private fun tileLat(ty: Int, y: Double, extent: Int, n: Int): Double {
        val py = (ty + y / extent) / n
        return atan(sinh(PI * (1 - 2 * py))) * 180 / PI
    }

    /**
     * Cuts a line exactly at the tile's edges (tiles carry a buffer beyond them). Neighbouring tiles
     * then end the same street at the same edge point, so the pieces join up in the street graph.
     */
    private fun clipToTile(xs: List<Double>, ys: List<Double>, size: Double, emit: (DoubleArray, DoubleArray) -> Unit) {
        val px = ArrayList<Double>(); val py = ArrayList<Double>()
        fun end() {
            if (px.size >= 2) emit(px.toDoubleArray(), py.toDoubleArray())
            px.clear(); py.clear()
        }
        for (i in 0 until xs.size - 1) {
            val x0 = xs[i]; val y0 = ys[i]; val dx = xs[i + 1] - x0; val dy = ys[i + 1] - y0
            // Liang–Barsky against [0, size] x [0, size]
            var t0 = 0.0; var t1 = 1.0; var ok = true
            for ((p, q) in arrayOf(-dx to x0, dx to size - x0, -dy to y0, dy to size - y0)) {
                if (p == 0.0) { if (q < 0) { ok = false; break } }
                else {
                    val r = q / p
                    if (p < 0) { if (r > t1) { ok = false; break }; if (r > t0) t0 = r }
                    else { if (r < t0) { ok = false; break }; if (r < t1) t1 = r }
                }
            }
            if (!ok) { end(); continue }
            val ax = x0 + t0 * dx; val ay = y0 + t0 * dy
            val bx = x0 + t1 * dx; val by = y0 + t1 * dy
            if (px.isEmpty() || t0 > 0.0) { end(); px.add(ax); py.add(ay) }
            px.add(bx); py.add(by)
            if (t1 < 1.0) end()
        }
        end()
    }

    private fun sinh(x: Double) = (exp(x) - exp(-x)) / 2
    private fun zigzag(v: Int) = (v ushr 1) xor -(v and 1)

    private fun packed(p: Pb): IntArray {
        val list = ArrayList<Int>()
        while (p.more()) list.add(p.varint().toInt())
        return list.toIntArray()
    }

    private fun value(p: Pb): String {
        var s = ""
        while (p.more()) {
            val tag = p.tag()
            when (tag ushr 3) {
                1 -> s = p.string()
                4, 5 -> s = p.varint().toString()
                6 -> { val v = p.varint(); s = ((v ushr 1) xor -(v and 1)).toString() }
                7 -> s = if (p.varint() != 0L) "true" else "false"
                else -> p.skip(tag and 7)
            }
        }
        return s
    }
}

enum class StreetMode { WALK, BUS, BIKE }

/**
 * A small street graph built from tile lines, with shortest paths (Dijkstra).
 * Points closer than ~2 m are merged so lines from neighbouring tiles join up. When walking, the
 * start and end may cut straight across open ground (parking lots, plazas) for a short distance,
 * but never through a building.
 */
class StreetRouter(
    lines: List<StreetLine>,
    private val mode: StreetMode,
    buildings: List<DoubleArray> = emptyList(),
) {
    private val lat = ArrayList<Double>()
    private val lon = ArrayList<Double>()
    private val adj = ArrayList<MutableList<Long>>() // (to shl 32) | weight in dm
    private val grid = HashMap<Long, MutableList<Int>>()
    private val segA = ArrayList<Int>()
    private val segB = ArrayList<Int>()
    /** Direction allowed on each segment: a→b, b→a. */
    private val segFwd = ArrayList<Boolean>()
    private val segBack = ArrayList<Boolean>()
    /** Cost per metre of each segment (1 = neutral). */
    private val segPen = ArrayList<Double>()

    private companion object {
        const val CELL = 0.00002 // ~2 m
        const val COARSE = 0.001 // ~100 m, for "nodes near a point"
        const val BCELL = 0.0005 // building edge grid
        const val SHORTCUT_M = 250.0
        const val SHORTCUT_COST = 2.0
        val WALK = setOf("minor", "service", "tertiary", "secondary", "primary", "trunk", "path", "track", "pedestrian", "living_street")
        val BUS = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "busway", "bus_guideway")
        val BIKE = setOf("trunk", "primary", "secondary", "tertiary", "minor", "service", "path", "track")
    }

    private fun cellKey(a: Long, b: Long) = (a shl 32) xor (b and 0xFFFFFFFFL)

    private fun addNode(la: Double, lo: Double): Int {
        val id = lat.size
        lat.add(la); lon.add(lo); adj.add(ArrayList(3))
        return id
    }

    private fun node(la: Double, lo: Double): Int {
        val a = floor(la / CELL).toLong(); val b = floor(lo / CELL).toLong()
        for (da in -1L..1L) for (db in -1L..1L) {
            for (id in grid[cellKey(a + da, b + db)] ?: continue) {
                if (Walking.meters(la, lo, lat[id], lon[id]) < 2.0) return id
            }
        }
        val id = addNode(la, lo)
        grid.getOrPut(cellKey(a, b)) { ArrayList(1) }.add(id)
        return id
    }

    private fun weight(m: Double) = (m * 10).roundToInt().coerceAtLeast(1).toLong()

    /** How much a cyclist dislikes a way, per metre: bike paths best, big roads worst. */
    private fun bikeCost(l: StreetLine): Double = when {
        l.isBikeway -> 0.75
        l.cls == "path" && (l.sub == "footway" || l.sub == "pedestrian") -> if (l.bike == "yes") 1.1 else 2.5
        l.cls == "path" -> 1.0
        l.cls == "minor" -> 1.0
        l.cls == "service" -> 1.2
        l.cls == "track" -> 1.3
        l.cls == "tertiary" -> 1.15
        l.cls == "secondary" -> 1.35
        l.cls == "primary" -> 1.6
        else -> 2.5 // trunk
    }
    private fun link(u: Int, v: Int, m: Double) { adj[u].add((v.toLong() shl 32) or weight(m)) }

    init {
        val allowed = when (mode) { StreetMode.WALK -> WALK; StreetMode.BUS -> BUS; StreetMode.BIKE -> BIKE }
        for (l in lines) {
            if (l.cls !in allowed) continue
            if (mode == StreetMode.BIKE && (l.bike == "no" || l.sub == "steps")) continue
            val penalty = if (mode == StreetMode.BIKE) bikeCost(l) else when {
                mode == StreetMode.WALK && (l.cls == "trunk" || l.cls == "primary") -> 1.3
                mode == StreetMode.BUS && l.cls == "service" -> 2.0
                mode == StreetMode.BUS && l.cls == "minor" -> 1.2
                else -> 1.0
            }
            var prev = node(l.lat[0], l.lon[0])
            for (i in 1 until l.lat.size) {
                val cur = node(l.lat[i], l.lon[i])
                if (cur == prev) continue
                val m = Walking.meters(lat[prev], lon[prev], lat[cur], lon[cur]) * penalty
                val free = mode == StreetMode.WALK || (mode == StreetMode.BIKE && l.cls == "path")
                val fwd = free || l.oneway >= 0
                val back = free || l.oneway <= 0
                if (fwd) link(prev, cur, m)
                if (back) link(cur, prev, m)
                segA.add(prev); segB.add(cur); segFwd.add(fwd); segBack.add(back); segPen.add(penalty)
                prev = cur
            }
        }
        closeGaps()
    }

    /**
     * A street that ends within a few metres of another street (a T-junction without a shared
     * point in the tile data) is joined to it, by splitting that street at the closest point.
     */
    private fun closeGaps() {
        val segCell = 0.0005
        val segGrid = HashMap<Long, MutableList<Int>>()
        for (s in segA.indices) {
            val a = segA[s]; val b = segB[s]
            val la0 = floor(minOf(lat[a], lat[b]) / segCell).toLong(); val la1 = floor(maxOf(lat[a], lat[b]) / segCell).toLong()
            val lo0 = floor(minOf(lon[a], lon[b]) / segCell).toLong(); val lo1 = floor(maxOf(lon[a], lon[b]) / segCell).toLong()
            if ((la1 - la0 + 1) * (lo1 - lo0 + 1) > 400) continue
            for (x in la0..la1) for (y in lo0..lo1) segGrid.getOrPut(cellKey(x, y)) { ArrayList(4) }.add(s)
        }
        val degree = IntArray(lat.size)
        for (s in segA.indices) { degree[segA[s]]++; degree[segB[s]]++ }
        for (v in degree.indices) {
            if (degree[v] != 1) continue
            val k = cos(lat[v] * PI / 180)
            var best = -1; var bestD = 4.0; var bestT = 0.0
            val key = cellKey(floor(lat[v] / segCell).toLong(), floor(lon[v] / segCell).toLong())
            for (s in segGrid[key] ?: continue) {
                val a = segA[s]; val b = segB[s]
                if (a == v || b == v) continue
                val dx = (lon[b] - lon[a]) * k; val dy = lat[b] - lat[a]
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((((lon[v] - lon[a]) * k) * dx + (lat[v] - lat[a]) * dy) / len2).coerceIn(0.0, 1.0)
                val d = Walking.meters(lat[v], lon[v], lat[a] + t * (lat[b] - lat[a]), lon[a] + t * (lon[b] - lon[a]))
                if (d < bestD) { bestD = d; best = s; bestT = t }
            }
            if (best < 0) continue
            val a = segA[best]; val b = segB[best]
            val j = when {
                bestT < 0.02 -> a
                bestT > 0.98 -> b
                else -> {
                    val jn = addNode(lat[a] + bestT * (lat[b] - lat[a]), lon[a] + bestT * (lon[b] - lon[a]))
                    val ma = Walking.meters(lat[a], lon[a], lat[jn], lon[jn]); val mb = Walking.meters(lat[jn], lon[jn], lat[b], lon[b])
                    val f = segFwd[best]; val bk = segBack[best]
                    if (f) { link(a, jn, ma); link(jn, b, mb) }
                    if (bk) { link(b, jn, mb); link(jn, a, ma) }
                    segB[best] = jn
                    segA.add(jn); segB.add(b); segFwd.add(f); segBack.add(bk); segPen.add(segPen[best])
                    jn
                }
            }
            val d = Walking.meters(lat[v], lon[v], lat[j], lon[j])
            link(v, j, d); link(j, v, d)
        }
    }

    // ---- buildings (walking shortcuts must not cross them)
    private val ringLat = ArrayList<DoubleArray>()
    private val ringLon = ArrayList<DoubleArray>()
    private val bEdges = HashMap<Long, MutableList<Long>>() // cell -> packed (ring shl 32 | edge)

    init {
        if (mode == StreetMode.WALK) for (r in buildings) {
            val n = r.size / 2
            if (n < 3) continue
            val id = ringLat.size
            ringLat.add(DoubleArray(n) { r[2 * it] }); ringLon.add(DoubleArray(n) { r[2 * it + 1] })
            val la = ringLat[id]; val lo = ringLon[id]
            for (e in 0 until n) {
                val f = (e + 1) % n
                val x0 = floor(minOf(la[e], la[f]) / BCELL).toLong(); val x1 = floor(maxOf(la[e], la[f]) / BCELL).toLong()
                val y0 = floor(minOf(lo[e], lo[f]) / BCELL).toLong(); val y1 = floor(maxOf(lo[e], lo[f]) / BCELL).toLong()
                if ((x1 - x0 + 1) * (y1 - y0 + 1) > 100) continue
                for (x in x0..x1) for (y in y0..y1) bEdges.getOrPut(cellKey(x, y)) { ArrayList(4) }.add((id.toLong() shl 32) or e.toLong())
            }
        }
    }

    private fun insideRings(la: Double, lo: Double): Set<Int> {
        val out = HashSet<Int>()
        for (packed in bEdges[cellKey(floor(la / BCELL).toLong(), floor(lo / BCELL).toLong())] ?: return out) {
            val r = (packed ushr 32).toInt()
            if (r in out) continue
            val xs = ringLon[r]; val ys = ringLat[r]
            var inside = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > la) != (ys[j] > la) && lo < (xs[j] - xs[i]) * (la - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
                j = i
            }
            if (inside) out.add(r)
        }
        return out
    }

    private fun crosses(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun o(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = o(cx, cy, dx, dy, ax, ay); val d2 = o(cx, cy, dx, dy, bx, by)
        val d3 = o(ax, ay, bx, by, cx, cy); val d4 = o(ax, ay, bx, by, dx, dy)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
    }

    /** True when a straight walk between two points doesn't go through a building (except [ignore]). */
    private fun clear(la1: Double, lo1: Double, la2: Double, lo2: Double, ignore: Set<Int>): Boolean {
        if (ringLat.isEmpty()) return true
        val x0 = floor(minOf(la1, la2) / BCELL).toLong(); val x1 = floor(maxOf(la1, la2) / BCELL).toLong()
        val y0 = floor(minOf(lo1, lo2) / BCELL).toLong(); val y1 = floor(maxOf(lo1, lo2) / BCELL).toLong()
        val seen = HashSet<Long>()
        for (x in x0..x1) for (y in y0..y1) {
            for (packed in bEdges[cellKey(x, y)] ?: continue) {
                if (!seen.add(packed)) continue
                val r = (packed ushr 32).toInt()
                if (r in ignore) continue
                val e = (packed and 0xFFFFFFFFL).toInt()
                val la = ringLat[r]; val lo = ringLon[r]; val f = (e + 1) % la.size
                if (crosses(lo1, la1, lo2, la2, lo[e], la[e], lo[f], la[f])) return false
            }
        }
        return true
    }

    // ---- nodes near a point (for shortcuts)
    private val coarse: HashMap<Long, MutableList<Int>> by lazy {
        val g = HashMap<Long, MutableList<Int>>()
        val degree = IntArray(lat.size)
        for (s in segA.indices) { degree[segA[s]]++; degree[segB[s]]++ }
        for (v in lat.indices) if (degree[v] > 0) g.getOrPut(cellKey(floor(lat[v] / COARSE).toLong(), floor(lon[v] / COARSE).toLong())) { ArrayList() }.add(v)
        g
    }

    /** Up to [max] street nodes within [radius] m that can be reached in a straight line. */
    private fun shortcuts(la: Double, lo: Double, radius: Double, ignore: Set<Int>, max: Int = 40): List<Pair<Int, Double>> {
        val a0 = floor(la / COARSE).toLong(); val b0 = floor(lo / COARSE).toLong()
        val r = 4L
        val cand = ArrayList<Pair<Int, Double>>()
        for (da in -r..r) for (db in -r..r) {
            for (v in coarse[cellKey(a0 + da, b0 + db)] ?: continue) {
                val d = Walking.meters(la, lo, lat[v], lon[v])
                if (d <= radius) cand.add(v to d)
            }
        }
        cand.sortBy { it.second }
        val out = ArrayList<Pair<Int, Double>>()
        for (c in cand) {
            if (out.size >= max) break
            if (clear(la, lo, lat[c.first], lon[c.first], ignore)) out.add(c)
        }
        return out
    }

    val isEmpty: Boolean get() = segA.isEmpty()

    /** Nearest point on any street segment: (segment index, t along it, distance m). */
    private fun snap(la: Double, lo: Double): Triple<Int, Double, Double>? {
        val k = cos(la * PI / 180)
        var best = -1; var bestT = 0.0; var bestD = Double.MAX_VALUE
        for (s in segA.indices) {
            val a = segA[s]; val b = segB[s]
            val ax = lon[a] * k; val ay = lat[a]; val bx = lon[b] * k; val by = lat[b]
            val px = lo * k; val py = la
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val qx = ax + t * dx - px; val qy = ay + t * dy - py
            val d = qx * qx + qy * qy
            if (d < bestD) { bestD = d; best = s; bestT = t }
        }
        if (best < 0) return null
        val a = segA[best]; val b = segB[best]
        val qLat = lat[a] + bestT * (lat[b] - lat[a]); val qLon = lon[a] + bestT * (lon[b] - lon[a])
        return Triple(best, bestT, Walking.meters(la, lo, qLat, qLon))
    }

    /**
     * Path from one point to another along streets, or null when there is no sensible one
     * (no streets nearby, not connected, or a big detour that suggests broken data).
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<Pair<Double, Double>>? {
        val straight = Walking.meters(fromLat, fromLon, toLat, toLon)
        if (straight < 30) return listOf(fromLat to fromLon, toLat to toLon)
        val s = snap(fromLat, fromLon) ?: return null
        val e = snap(toLat, toLon) ?: return null
        if (s.third > 250 || e.third > 250) return null
        val walk = mode == StreetMode.WALK

        val n = lat.size
        val src = n; val dst = n + 1
        val dist = DoubleArray(n + 2) { Double.MAX_VALUE }
        val prev = IntArray(n + 2) { -1 }
        fun pointOn(seg: Int, t: Double): Pair<Double, Double> {
            val a = segA[seg]; val b = segB[seg]
            return (lat[a] + t * (lat[b] - lat[a])) to (lon[a] + t * (lon[b] - lon[a]))
        }
        val sp = pointOn(s.first, s.second); val ep = pointOn(e.first, e.second)
        val sa = segA[s.first]; val sb = segB[s.first]; val ea = segA[e.first]; val eb = segB[e.first]

        // Ways out of the start: onto the nearest street, or (walking) straight across open ground.
        val srcEdges = HashMap<Int, Double>()      // node -> cost
        val srcViaSnap = HashSet<Int>()             // reached through the snapped street point
        fun addSrc(v: Int, c: Double, viaSnap: Boolean) {
            if (c < (srcEdges[v] ?: Double.MAX_VALUE)) {
                srcEdges[v] = c
                if (viaSnap) srcViaSnap.add(v) else srcViaSnap.remove(v)
            }
        }
        val sPen = segPen[s.first]; val ePen = segPen[e.first]
        if (segBack[s.first]) addSrc(sa, s.third + Walking.meters(sp.first, sp.second, lat[sa], lon[sa]) * sPen, true)
        if (segFwd[s.first]) addSrc(sb, s.third + Walking.meters(sp.first, sp.second, lat[sb], lon[sb]) * sPen, true)
        val fromIgnore = if (walk) insideRings(fromLat, fromLon) else emptySet()
        val toIgnore = if (walk) insideRings(toLat, toLon) else emptySet()
        if (walk) shortcuts(fromLat, fromLon, SHORTCUT_M, fromIgnore).forEach { (v, d) -> addSrc(v, d * SHORTCUT_COST, false) }

        // Ways into the destination.
        val dstEdges = HashMap<Int, Double>()
        val dstViaSnap = HashSet<Int>()
        fun addDst(v: Int, c: Double, viaSnap: Boolean) {
            if (c < (dstEdges[v] ?: Double.MAX_VALUE)) {
                dstEdges[v] = c
                if (viaSnap) dstViaSnap.add(v) else dstViaSnap.remove(v)
            }
        }
        if (segFwd[e.first]) addDst(ea, e.third + Walking.meters(lat[ea], lon[ea], ep.first, ep.second) * ePen, true)
        if (segBack[e.first]) addDst(eb, e.third + Walking.meters(lat[eb], lon[eb], ep.first, ep.second) * ePen, true)
        if (walk) shortcuts(toLat, toLon, SHORTCUT_M, toIgnore).forEach { (v, d) -> addDst(v, d * SHORTCUT_COST, false) }

        var direct = Double.MAX_VALUE
        if (walk && straight <= SHORTCUT_M && clear(fromLat, fromLon, toLat, toLon, fromIgnore + toIgnore)) direct = straight * SHORTCUT_COST
        if (s.first == e.first && (if (e.second >= s.second) segFwd[s.first] else segBack[s.first])) {
            direct = minOf(direct, s.third + Walking.meters(sp.first, sp.second, ep.first, ep.second) * sPen + e.third)
        }

        val heap = DoubleHeap()
        dist[src] = 0.0; heap.push(src, 0.0)
        while (heap.isNotEmpty()) {
            val (u, du) = heap.pop()
            if (du > dist[u]) continue
            if (u == dst) break
            fun relax(v: Int, w: Double) {
                val nd = du + w
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; heap.push(v, nd) }
            }
            if (u == src) {
                srcEdges.forEach { (v, c) -> relax(v, c) }
                if (direct < Double.MAX_VALUE) relax(dst, direct)
            } else {
                for (packed in adj[u]) relax((packed ushr 32).toInt(), (packed and 0xFFFFFFFFL) / 10.0)
                dstEdges[u]?.let { relax(dst, it) }
            }
        }
        if (dist[dst] == Double.MAX_VALUE) return null
        if (dist[dst] > straight * 3 + 400) return null

        val ids = ArrayList<Int>()
        var c = prev[dst]
        while (c != src && c >= 0) { ids.add(c); c = prev[c] }
        ids.reverse()
        val out = ArrayList<Pair<Double, Double>>(ids.size + 4)
        out.add(fromLat to fromLon)
        if (ids.isEmpty()) {
            // direct: straight across, or along the one shared street
            if (s.first == e.first && dist[dst] < straight * SHORTCUT_COST - 0.01) { out.add(sp); out.add(ep) }
        } else {
            if (ids.first() in srcViaSnap) out.add(sp)
            ids.forEach { out.add(lat[it] to lon[it]) }
            if (ids.last() in dstViaSnap) out.add(ep)
        }
        out.add(toLat to toLon)
        return dropLoops(out, if (walk) 20.0 else 6.0)
    }

    /** Along-street path through several points (bus stops), straight where a piece can't be routed. */
    fun through(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val piece = route(a.first, a.second, b.first, b.second) ?: listOf(a, b)
            if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            out.addAll(piece)
        }
        return dropLoops(out)
    }
}

/**
 * Removes out-and-back spurs and small loops: whenever the path comes back within a few metres of
 * an earlier point, the part in between is dropped.
 */
fun dropLoops(p: List<Pair<Double, Double>>, tolM: Double = 6.0): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>(p.size)
    for (pt in p) {
        var cut = -1
        // look back a limited distance so long legitimate loops (a bus circling a block) survive
        for (j in out.size - 2 downTo maxOf(0, out.size - 60)) {
            if (Walking.meters(out[j].first, out[j].second, pt.first, pt.second) < tolM) cut = j
        }
        if (cut >= 0) {
            val gap = Walking.meters(out[cut].first, out[cut].second, pt.first, pt.second)
            val removed = pathMeters(out.subList(cut, out.size) + listOf(pt))
            // only real detours: much longer than the gap they go around
            if (removed < 400 && removed > gap * 2.5) {
                while (out.size > cut + 1) out.removeAt(out.lastIndex)
                if (gap < 1.0) continue // back at the same spot: keep just one point
            }
        }
        out.add(pt)
    }
    return out
}

fun pathMeters(p: List<Pair<Double, Double>>): Int {
    var m = 0.0
    for (i in 1 until p.size) m += Walking.meters(p[i - 1].first, p[i - 1].second, p[i].first, p[i].second)
    return m.toInt()
}

/** Minimal binary heap of (node, priority). */
private class DoubleHeap {
    private var ids = IntArray(64)
    private var pr = DoubleArray(64)
    private var size = 0

    fun isNotEmpty() = size > 0

    fun push(id: Int, p: Double) {
        if (size == ids.size) { ids = ids.copyOf(size * 2); pr = pr.copyOf(size * 2) }
        var i = size++
        ids[i] = id; pr[i] = p
        while (i > 0) {
            val parent = (i - 1) / 2
            if (pr[parent] <= pr[i]) break
            swap(i, parent); i = parent
        }
    }

    fun pop(): Pair<Int, Double> {
        val top = ids[0] to pr[0]
        size--
        ids[0] = ids[size]; pr[0] = pr[size]
        var i = 0
        while (true) {
            val l = 2 * i + 1; val r = l + 1
            var m = i
            if (l < size && pr[l] < pr[m]) m = l
            if (r < size && pr[r] < pr[m]) m = r
            if (m == i) break
            swap(i, m); i = m
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val t = ids[a]; ids[a] = ids[b]; ids[b] = t
        val q = pr[a]; pr[a] = pr[b]; pr[b] = q
    }
}
