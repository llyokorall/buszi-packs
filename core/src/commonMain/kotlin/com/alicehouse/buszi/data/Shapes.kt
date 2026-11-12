package com.alicehouse.buszi.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The road a bus really takes (GTFS shapes.txt), stored per timetable pattern. Points are
 * simplified (a few metres off at most) and packed as zig-zag varint deltas of 1e-5 degrees
 * (~1 m), so a 50 km intercity line takes a few kB.
 */
object ShapeCodec {
    private const val SCALE = 100_000.0

    /** Packs [lat] / [lon] (same length). */
    fun encode(lat: DoubleArray, lon: DoubleArray): ByteArray {
        val out = ArrayList<Byte>(lat.size * 4)
        fun put(v: Long) {
            var z = (v shl 1) xor (v shr 63)
            while (z and 0x7FL.inv() != 0L) { out.add(((z and 0x7F) or 0x80).toByte()); z = z ushr 7 }
            out.add(z.toByte())
        }
        var pa = 0L; var po = 0L
        for (i in lat.indices) {
            val a = (lat[i] * SCALE).roundToLong(); val o = (lon[i] * SCALE).roundToLong()
            put(a - pa); put(o - po); pa = a; po = o
        }
        return out.toByteArray()
    }

    /** Unpacks to (lat, lon) points. */
    fun decode(bytes: ByteArray): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(bytes.size / 3)
        var i = 0
        fun next(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val b = bytes[i++].toLong()
                r = r or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
            }
            return (r ushr 1) xor -(r and 1)
        }
        var a = 0L; var o = 0L
        while (i < bytes.size) {
            a += next(); if (i >= bytes.size) break
            o += next()
            out.add(a / SCALE to o / SCALE)
        }
        return out
    }

    /** Douglas–Peucker: indexes of the points to keep so the line moves at most [tolM] metres. */
    fun simplify(lat: DoubleArray, lon: DoubleArray, tolM: Double = 3.0): IntArray {
        val n = lat.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to n - 1)
        val kx = 111_320.0 * cos(lat[0] * kotlin.math.PI / 180); val ky = 110_540.0
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e - s < 2) continue
            val ax = lon[s] * kx; val ay = lat[s] * ky; val bx = lon[e] * kx; val by = lat[e] * ky
            var worst = -1.0; var at = -1
            for (k in s + 1 until e) {
                val d = segDist(lon[k] * kx, lat[k] * ky, ax, ay, bx, by)
                if (d > worst) { worst = d; at = k }
            }
            if (worst > tolM) { keep[at] = true; stack.addLast(s to at); stack.addLast(at to e) }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx - px; val y = ay + t * dy - py
        return sqrt(x * x + y * y)
    }

    /** Closest point on the shape to (lat, lon) searching segments from [startSeg]: (segment, t, metres). */
    private fun project(shape: List<Pair<Double, Double>>, lat: Double, lon: Double, startSeg: Int, startT: Double): Triple<Int, Double, Double>? {
        val kx = 111_320.0 * cos(lat * kotlin.math.PI / 180); val ky = 110_540.0
        val all = ArrayList<Triple<Int, Double, Double>>()
        for (s in startSeg until shape.size - 1) {
            val ax = shape[s].second * kx; val ay = shape[s].first * ky
            val bx = shape[s + 1].second * kx; val by = shape[s + 1].first * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val px = lon * kx; val py = lat * ky
            var t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            if (s == startSeg && t < startT) t = startT
            val x = ax + t * dx - px; val y = ay + t * dy - py
            all.add(Triple(s, t, sqrt(x * x + y * y)))
        }
        // The earliest pass near the stop, not a slightly closer later one: a road used both ways
        // (out and back) has the stop ~10-20 m from both passes, and the bus is on the first.
        val min = all.minOfOrNull { it.third } ?: return null
        var i = all.indexOfFirst { it.third <= min + 25.0 }
        while (i + 1 < all.size && all[i + 1].third <= all[i].third) i++ // closest point of that pass
        return all[i]
    }

    /**
     * The part of [shape] between two stops, in travel order: from the point closest to the
     * first stop to the point closest to the last one after it. Every stop in [stops] is matched in
     * order, so a route passing the same place twice (loops) follows the right pass. Null when a
     * stop is more than [maxOffM] metres from the shape (wrong or missing shape).
     */
    fun slice(shape: List<Pair<Double, Double>>, stops: List<Pair<Double, Double>>, maxOffM: Double = 120.0): List<Pair<Double, Double>>? {
        if (shape.size < 2 || stops.size < 2) return null
        var seg = 0; var t = 0.0
        val cuts = ArrayList<Pair<Int, Double>>(stops.size)
        for ((la, lo) in stops) {
            val p = project(shape, la, lo, seg, t) ?: return null
            if (p.third > maxOffM) return null
            seg = p.first; t = p.second
            cuts.add(seg to t)
        }
        fun at(s: Int, tt: Double): Pair<Double, Double> {
            val a = shape[s]; val b = shape[s + 1]
            return (a.first + (b.first - a.first) * tt) to (a.second + (b.second - a.second) * tt)
        }
        val (s0, t0) = cuts.first(); val (s1, t1) = cuts.last()
        val out = ArrayList<Pair<Double, Double>>()
        out.add(at(s0, t0))
        for (k in s0 + 1..s1) out.add(shape[k])
        out.add(at(s1, t1))
        return out.filterIndexed { i, p -> i == 0 || abs(p.first - out[i - 1].first) + abs(p.second - out[i - 1].second) > 1e-7 }
    }
}
