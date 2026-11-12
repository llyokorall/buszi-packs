package com.alicehouse.buszi.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/** What kind of way a piece of a bike route is on (for the coloured bar and the step list). */
enum class RoadKind { BIKE_PATH, QUIET, MAIN }

enum class Turn { START, STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN }

/**
 * One instruction: "[turn] onto [name]", then ride [meters] on [kind] of way.
 * [bearing] is the compass direction at the start (0 = north), for "Head north".
 * [at] is the index in the route points where the step starts.
 */
data class DirStep(val turn: Turn, val name: String?, val kind: RoadKind, val meters: Int, val bearing: Int, val at: Int)

class RouteDirections(
    val points: List<Pair<Double, Double>>,
    /** Kind of way for each segment (points[i] -> points[i + 1]). */
    val kinds: List<RoadKind>,
    val steps: List<DirStep>,
) {
    val meters: Int get() = pathMeters(points)

    /** Metres on each kind of way. */
    fun metersBy(kind: RoadKind): Int {
        var m = 0.0
        for (i in kinds.indices) if (kinds[i] == kind) m += Walking.meters(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
        return m.toInt()
    }
}

fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val k = cos(a.first * PI / 180)
    val dx = (b.second - a.second) * k; val dy = b.first - a.first
    val deg = atan2(dx, dy) * 180 / PI
    return (deg + 360) % 360
}

private fun kindOf(l: StreetLine): RoadKind = when {
    l.isBikeway -> RoadKind.BIKE_PATH
    l.cls in setOf("primary", "secondary", "trunk", "motorway") -> RoadKind.MAIN
    l.cls == "tertiary" -> RoadKind.MAIN
    else -> RoadKind.QUIET
}

/** Index of line segments for "which way is this point on?". */
private class SegIndex<T>(items: List<T>, lat: (T) -> DoubleArray, lon: (T) -> DoubleArray) {
    private val cell = 0.0005
    private val grid = HashMap<Long, MutableList<Pair<T, Int>>>()
    private val la = lat; private val lo = lon
    private fun key(a: Long, b: Long) = (a shl 32) xor (b and 0xFFFFFFFFL)

    init {
        for (it in items) {
            val ys = lat(it); val xs = lon(it)
            for (i in 0 until ys.size - 1) {
                val y0 = floor(minOf(ys[i], ys[i + 1]) / cell).toLong(); val y1 = floor(maxOf(ys[i], ys[i + 1]) / cell).toLong()
                val x0 = floor(minOf(xs[i], xs[i + 1]) / cell).toLong(); val x1 = floor(maxOf(xs[i], xs[i + 1]) / cell).toLong()
                if ((y1 - y0 + 1) * (x1 - x0 + 1) > 64) continue
                for (y in y0..y1) for (x in x0..x1) grid.getOrPut(key(y, x)) { ArrayList(2) }.add(it to i)
            }
        }
    }

    /** Nearest item within [maxM] metres of a point, preferring ones running the same way as [dir]. */
    fun nearest(p: Pair<Double, Double>, maxM: Double, dir: Double?): T? {
        val y = floor(p.first / cell).toLong(); val x = floor(p.second / cell).toLong()
        var best: T? = null; var bestScore = Double.MAX_VALUE
        for (dy in -1L..1L) for (dx in -1L..1L) {
            for ((item, i) in grid[key(y + dy, x + dx)] ?: continue) {
                val ys = la(item); val xs = lo(item)
                val d = segDist(p, ys[i] to xs[i], ys[i + 1] to xs[i + 1])
                if (d > maxM) continue
                var score = d
                if (dir != null) {
                    val b = bearing(ys[i] to xs[i], ys[i + 1] to xs[i + 1])
                    var diff = abs(b - dir) % 180
                    if (diff > 90) diff = 180 - diff
                    score += diff / 6 // 90° off counts like 15 m further away
                }
                if (score < bestScore) { bestScore = score; best = item }
            }
        }
        return best
    }

    private fun segDist(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val k = cos(p.first * PI / 180)
        val ax = a.second * k; val ay = a.first; val bx = b.second * k; val by = b.first; val px = p.second * k
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (p.first - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return Walking.meters(p.first, p.second, a.first + t * (b.first - a.first), a.second + t * (b.second - a.second))
    }
}

private fun turnFor(delta: Double): Turn {
    val d = ((delta + 540) % 360) - 180 // -180..180, positive = right
    return when {
        abs(d) < 25 -> Turn.STRAIGHT
        abs(d) < 60 -> if (d > 0) Turn.SLIGHT_RIGHT else Turn.SLIGHT_LEFT
        abs(d) < 135 -> if (d > 0) Turn.RIGHT else Turn.LEFT
        abs(d) < 170 -> if (d > 0) Turn.SHARP_RIGHT else Turn.SHARP_LEFT
        else -> Turn.U_TURN
    }
}

/** Heading over the first/last ~[m] metres of a run of points. */
private fun headingFrom(points: List<Pair<Double, Double>>, start: Int, end: Int, forward: Boolean, m: Double = 20.0): Double {
    if (forward) {
        var j = start + 1
        while (j < end && Walking.meters(points[start].first, points[start].second, points[j].first, points[j].second) < m) j++
        return bearing(points[start], points[minOf(j, end)])
    } else {
        var j = end - 1
        while (j > start && Walking.meters(points[j].first, points[j].second, points[end].first, points[end].second) < m) j--
        return bearing(points[maxOf(j, start)], points[end])
    }
}

/**
 * Turn-by-turn directions for a route, using street lines (for the kind of way) and named lines
 * (for street names) from the map tiles.
 */
fun directionsFor(points: List<Pair<Double, Double>>, lines: List<StreetLine>, names: List<NamedLine>): RouteDirections {
    if (points.size < 2) return RouteDirections(points, emptyList(), emptyList())
    val lineIdx = SegIndex(lines, { it.lat }, { it.lon })
    val nameIdx = SegIndex(names, { it.lat }, { it.lon })
    val n = points.size - 1
    val kinds = ArrayList<RoadKind>(n)
    val segNames = ArrayList<String?>(n)
    for (i in 0 until n) {
        val a = points[i]; val b = points[i + 1]
        val mid = ((a.first + b.first) / 2) to ((a.second + b.second) / 2)
        val dir = bearing(a, b)
        kinds.add(lineIdx.nearest(mid, 10.0, dir)?.let { kindOf(it) } ?: RoadKind.QUIET)
        segNames.add(nameIdx.nearest(mid, 18.0, dir)?.name)
    }
    // Smooth one-off blips (a crossing, a gap in the name data) shorter than 30 m.
    fun segLen(i: Int) = Walking.meters(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
    for (i in 1 until n - 1) {
        if (segNames[i] != segNames[i - 1] && segNames[i - 1] == segNames[i + 1] && segLen(i) < 30) segNames[i] = segNames[i - 1]
        if (kinds[i] != kinds[i - 1] && kinds[i - 1] == kinds[i + 1] && segLen(i) < 30) kinds[i] = kinds[i - 1]
    }
    // Group segments into steps: a new step where the street name changes (or, without names, the kind).
    data class Run(val from: Int, var to: Int, val name: String?, val kind: RoadKind)
    val runs = ArrayList<Run>()
    for (i in 0 until n) {
        val last = runs.lastOrNull()
        val same = last != null && (if (segNames[i] != null || last.name != null) segNames[i] == last.name else kinds[i] == last.kind)
        if (same) last!!.to = i + 1 else runs.add(Run(i, i + 1, segNames[i], kinds[i]))
    }
    // Merge very short runs into the previous one (noise at junctions).
    val merged = ArrayList<Run>()
    for (r in runs) {
        val len = pathMeters(points.subList(r.from, r.to + 1))
        val prev = merged.lastOrNull()
        if (prev != null && len < 25) prev.to = r.to else merged.add(r)
    }
    val steps = merged.mapIndexed { idx, r ->
        val len = pathMeters(points.subList(r.from, r.to + 1))
        val out = headingFrom(points, r.from, r.to, forward = true)
        val turn = if (idx == 0) Turn.START else turnFor(out - headingFrom(points, merged[idx - 1].from, merged[idx - 1].to, forward = false))
        // the kind that covers most of the run
        val byKind = HashMap<RoadKind, Double>()
        for (i in r.from until r.to) byKind[kinds[i]] = (byKind[kinds[i]] ?: 0.0) + segLen(i)
        DirStep(turn, r.name, byKind.maxByOrNull { it.value }?.key ?: r.kind, len, out.roundToInt() % 360, r.from)
    }
    return RouteDirections(points, kinds, steps)
}
