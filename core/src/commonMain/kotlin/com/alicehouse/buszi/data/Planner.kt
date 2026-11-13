package com.alicehouse.buszi.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Offline trip planner (RAPTOR: round-based public transit routing) over the compact timetable.
 * Pure Kotlin so the same code runs on Android and iOS. Everything works on integer stop and
 * pattern indexes; the app maps them back to GTFS ids.
 *
 * Times are seconds after the start of today's service day. Trips of yesterday's service day that
 * run past midnight (GTFS times like 25:10) are included with a -24 h shift.
 */

object Walking {
    const val DEFAULT_SPEED_MPS = 1.25 // ~4.5 km/h
    const val DETOUR = 1.3 // streets are not straight lines

    /** The user's walking pace (set from the app's preferences). */
    var speedMps: Double = DEFAULT_SPEED_MPS

    fun seconds(meters: Double): Int = (meters * DETOUR / speedMps).toInt()

    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/** A stop that can be reached on foot from a place, and how long the walk is. */
data class Access(val stop: Int, val walkSec: Int, val meters: Int)

sealed class PlanLeg {
    abstract val startSec: Int
    abstract val endSec: Int

    /** [fromStop] / [toStop] are -1 for the origin / destination place. */
    data class Walk(
        val fromStop: Int,
        val toStop: Int,
        override val startSec: Int,
        override val endSec: Int,
        val meters: Int,
    ) : PlanLeg()

    data class Ride(
        val pattern: Int,
        /** Index into the pattern's trips. */
        val trip: Int,
        /** 0, or -86400 for a trip of yesterday's service day. */
        val dayShift: Int,
        val boardPos: Int,
        val alightPos: Int,
        override val startSec: Int,
        override val endSec: Int,
    ) : PlanLeg()
}

data class Journey(val legs: List<PlanLeg>) {
    val departSec: Int get() = legs.first().startSec
    val arriveSec: Int get() = legs.last().endSec
    val rides: Int get() = legs.count { it is PlanLeg.Ride }
    val walkMeters: Int get() = legs.sumOf { (it as? PlanLeg.Walk)?.meters ?: 0 }
    val firstRide: PlanLeg.Ride? get() = legs.firstOrNull { it is PlanLeg.Ride } as PlanLeg.Ride?
}

class PlanNetwork(
    val stopLat: DoubleArray,
    val stopLon: DoubleArray,
    val patternStops: Array<IntArray>,
    val patternOffsets: Array<IntArray>,
    val patternBoard: Array<BooleanArray>,
    /** Trip start seconds per pattern, ascending. */
    val tripStart: Array<IntArray>,
    val tripService: Array<IntArray>,
    maxTransferM: Double = 300.0,
) {
    val stopCount: Int = stopLat.size
    val stopPatterns: Array<IntArray>
    val transferTo: Array<IntArray>
    val transferSec: Array<IntArray>
    val transferMeters: Array<IntArray>

    private val cell = 0.003 // ~330 m of latitude
    private val grid = HashMap<Long, MutableList<Int>>()

    private fun key(lat: Double, lon: Double): Long {
        val a = kotlin.math.floor(lat / cell).toLong()
        val b = kotlin.math.floor(lon / cell).toLong()
        return (a shl 32) xor (b and 0xFFFFFFFFL)
    }

    init {
        val byStop = Array(stopCount) { ArrayList<Int>(2) }
        for (p in patternStops.indices) for (s in patternStops[p].distinct()) byStop[s].add(p)
        stopPatterns = Array(stopCount) { byStop[it].toIntArray() }

        for (s in 0 until stopCount) grid.getOrPut(key(stopLat[s], stopLon[s])) { ArrayList(4) }.add(s)
        val to = Array(stopCount) { IntArray(0) }
        val secs = Array(stopCount) { IntArray(0) }
        val ms = Array(stopCount) { IntArray(0) }
        for (s in 0 until stopCount) {
            if (stopPatterns[s].isEmpty()) continue
            val near = near(stopLat[s], stopLon[s], maxTransferM).filter { it.first != s && stopPatterns[it.first].isNotEmpty() }
            to[s] = IntArray(near.size) { near[it].first }
            ms[s] = IntArray(near.size) { near[it].second.toInt() }
            secs[s] = IntArray(near.size) { Walking.seconds(near[it].second) + 30 }
        }
        transferTo = to; transferSec = secs; transferMeters = ms
    }

    /** Stops within [radiusM] of a point, with their straight-line distance. */
    fun near(lat: Double, lon: Double, radiusM: Double): List<Pair<Int, Double>> {
        val n = ceil(radiusM / 250.0).toInt() + 1 // a cell is at least ~250 m wide at 41-60°N
        val a0 = kotlin.math.floor(lat / cell).toLong()
        val b0 = kotlin.math.floor(lon / cell).toLong()
        val out = ArrayList<Pair<Int, Double>>()
        for (da in -n..n) for (db in -n..n) {
            val k = ((a0 + da) shl 32) xor ((b0 + db) and 0xFFFFFFFFL)
            for (s in grid[k] ?: continue) {
                val d = Walking.meters(lat, lon, stopLat[s], stopLon[s])
                if (d <= radiusM) out.add(s to d)
            }
        }
        return out
    }

    /** Stops to walk to from a place: within 800 m, widened up to 2 km when nothing is that close. */
    fun access(lat: Double, lon: Double): List<Access> {
        for (r in doubleArrayOf(800.0, 1300.0, 2000.0)) {
            val list = near(lat, lon, r).filter { stopPatterns[it.first].isNotEmpty() }
            if (list.isNotEmpty()) return list.map { Access(it.first, Walking.seconds(it.second), it.second.toInt()) }
        }
        return emptyList()
    }
}

/** One region's timetable for the planner; stop indexes in [patternStops] are local to the part. */
class PlanPartData(
    val stopLat: DoubleArray,
    val stopLon: DoubleArray,
    val patternStops: Array<IntArray>,
    val patternOffsets: Array<IntArray>,
    val patternBoard: Array<BooleanArray>,
    val tripStart: Array<IntArray>,
    val tripService: Array<IntArray>,
    /** Highest service number used by this part. */
    val maxService: Int,
)

/**
 * Several regions' timetables as one network (e.g. Nanaimo + Cowichan Valley + Victoria), so a
 * journey can change between their buses where their stops are close. Stops and patterns are
 * numbered part after part; part p's service numbers are shifted by [serviceBase] so they don't clash.
 */
class MergedNetwork(parts: List<PlanPartData>) {
    val stopBase = IntArray(parts.size)
    val patternBase = IntArray(parts.size)
    val serviceBase = IntArray(parts.size)
    val stopPart: IntArray
    val patternPart: IntArray
    val maxService: Int
    val net: PlanNetwork

    init {
        var s = 0; var p = 0; var v = 0
        parts.forEachIndexed { i, d ->
            stopBase[i] = s; patternBase[i] = p; serviceBase[i] = v
            s += d.stopLat.size; p += d.patternStops.size; v += d.maxService + 1
        }
        maxService = v - 1
        stopPart = IntArray(s); patternPart = IntArray(p)
        parts.forEachIndexed { i, d ->
            for (k in d.stopLat.indices) stopPart[stopBase[i] + k] = i
            for (k in d.patternStops.indices) patternPart[patternBase[i] + k] = i
        }
        fun <T> all(pick: (Int, PlanPartData) -> List<T>): List<T> = parts.flatMapIndexed { i, d -> pick(i, d) }
        net = PlanNetwork(
            stopLat = parts.fold(DoubleArray(0)) { a, d -> a + d.stopLat },
            stopLon = parts.fold(DoubleArray(0)) { a, d -> a + d.stopLon },
            patternStops = all { i, d -> d.patternStops.map { st -> IntArray(st.size) { st[it] + stopBase[i] } } }.toTypedArray(),
            patternOffsets = all { _, d -> d.patternOffsets.asList() }.toTypedArray(),
            patternBoard = all { _, d -> d.patternBoard.asList() }.toTypedArray(),
            tripStart = all { _, d -> d.tripStart.asList() }.toTypedArray(),
            tripService = all { i, d ->
                d.tripService.map { sv -> if (serviceBase[i] == 0) sv else IntArray(sv.size) { sv[it] + serviceBase[i] } }
            }.toTypedArray(),
        )
    }

    /** Services running on one day, as one array over all parts; [byPart] lists each part's own numbers. */
    fun active(byPart: List<Collection<Int>>): BooleanArray {
        val a = BooleanArray(maxService + 1)
        // A service with no trips may be numbered past the part's last one: it must not spill into the next part.
        byPart.forEachIndexed { i, set -> set.forEach { if (it in 0..partMax[i]) a[it + serviceBase[i]] = true } }
        return a
    }

    private val partMax = IntArray(parts.size) { parts[it].maxService }
}

class Raptor(private val net: PlanNetwork) {

    private companion object {
        const val INF = Int.MAX_VALUE
        const val NONE: Byte = 0
        const val ACCESS: Byte = 1
        const val RIDE: Byte = 2
        const val TRANSFER: Byte = 3
        const val DAY = 86_400
        const val MIN_CHANGE_SEC = 60
    }

    /**
     * Fastest journeys leaving at or after [departSec]: one per number of rides that still
     * arrives earlier (fewer changes vs. earlier arrival). [today] / [yesterday] are the services
     * running on those service days, indexed by service number.
     */
    fun route(
        origins: List<Access>,
        dests: List<Access>,
        departSec: Int,
        today: BooleanArray,
        yesterday: BooleanArray,
        maxRides: Int = 4,
    ): List<Journey> {
        val n = net.stopCount
        val rounds = maxRides + 1
        val arr = Array(rounds) { IntArray(n) { INF } }
        val kind = Array(rounds) { ByteArray(n) }
        val pPattern = Array(rounds) { IntArray(n) }
        val pTrip = Array(rounds) { IntArray(n) }
        val pShift = Array(rounds) { IntArray(n) }
        val pBoard = Array(rounds) { IntArray(n) }
        val pAlight = Array(rounds) { IntArray(n) }
        val best = IntArray(n) { INF }
        val egress = IntArray(n) { -1 }
        for (d in dests) if (egress[d.stop] < 0 || d.walkSec < egress[d.stop]) egress[d.stop] = d.walkSec
        val accessOf = HashMap<Int, Access>()

        var marked = BooleanArray(n)
        for (o in origins) {
            val t = departSec + o.walkSec
            if (t < arr[0][o.stop]) {
                arr[0][o.stop] = t; best[o.stop] = t; kind[0][o.stop] = ACCESS; marked[o.stop] = true
                accessOf[o.stop] = o
            }
        }

        val out = ArrayList<Journey>()
        var bestDest = INF
        val queue = IntArray(net.patternStops.size) { -1 }
        val queued = ArrayList<Int>()

        for (k in 1 until rounds) {
            val prev = arr[k - 1]
            val cur = arr[k]
            prev.copyInto(cur)

            // Patterns through the stops improved last round, from the earliest such stop.
            queued.clear()
            for (s in 0 until n) {
                if (!marked[s]) continue
                for (p in net.stopPatterns[s]) {
                    val stops = net.patternStops[p]
                    var pos = -1
                    for (i in stops.indices) if (stops[i] == s) { pos = i; break }
                    if (pos < 0) continue
                    if (queue[p] < 0) { queue[p] = pos; queued.add(p) } else if (pos < queue[p]) queue[p] = pos
                }
            }
            val newMarked = BooleanArray(n)
            var any = false

            for (p in queued) {
                val startPos = queue[p]; queue[p] = -1
                val stops = net.patternStops[p]
                val offs = net.patternOffsets[p]
                val board = net.patternBoard[p]
                val starts = net.tripStart[p]
                var trip = -1; var shift = 0; var boardPos = -1
                for (i in startPos until stops.size) {
                    val s = stops[i]
                    if (trip >= 0 && i > boardPos) {
                        val t = starts[trip] + shift + offs[i]
                        if (t < best[s] && t < bestDest) {
                            cur[s] = t; best[s] = t; kind[k][s] = RIDE
                            pPattern[k][s] = p; pTrip[k][s] = trip; pShift[k][s] = shift
                            pBoard[k][s] = boardPos; pAlight[k][s] = i
                            newMarked[s] = true; any = true
                        }
                    }
                    val ready0 = prev[s]
                    if (ready0 == INF || !board[i]) continue
                    val ready = ready0 + if (kind[k - 1][s] == RIDE) MIN_CHANGE_SEC else 0
                    if (trip >= 0 && starts[trip] + shift + offs[i] <= ready) continue
                    val (nt, ns) = earliestTrip(p, offs[i], ready, today, yesterday) ?: continue
                    if (trip < 0 || starts[nt] + ns < starts[trip] + shift) { trip = nt; shift = ns; boardPos = i }
                }
            }

            // Short walks to nearby stops after riding.
            for (s in 0 until n) {
                if (!newMarked[s] || kind[k][s] != RIDE) continue
                val to = net.transferTo[s]; val secs = net.transferSec[s]
                for (j in to.indices) {
                    val m = to[j]
                    val t = cur[s] + secs[j]
                    if (t < best[m] && t < bestDest) {
                        cur[m] = t; best[m] = t; kind[k][m] = TRANSFER; pPattern[k][m] = s; pTrip[k][m] = j
                        newMarked[m] = true
                    }
                }
            }

            // Best arrival at the destination with exactly k rides.
            var bestStop = -1; var bestT = bestDest
            for (d in dests) {
                if (cur[d.stop] == INF || kind[k][d.stop] == NONE) continue
                val t = cur[d.stop] + egress[d.stop]
                if (t < bestT) { bestT = t; bestStop = d.stop }
            }
            if (bestStop >= 0) {
                bestDest = bestT
                trace(k, bestStop, arr, kind, pPattern, pTrip, pShift, pBoard, pAlight, accessOf, departSec)
                    ?.let { legs -> out.add(Journey(legs + PlanLeg.Walk(bestStop, -1, cur[bestStop], bestT, egressMeters(dests, bestStop)))) }
            }
            if (!any) break
            marked = newMarked
        }
        return out.map { tighten(it) }
    }

    private fun egressMeters(dests: List<Access>, stop: Int) = dests.filter { it.stop == stop }.minOf { it.meters }

    /** Earliest trip of pattern [p] leaving a stop at [off] not before [ready]: (trip index, day shift). */
    private fun earliestTrip(p: Int, off: Int, ready: Int, today: BooleanArray, yesterday: BooleanArray): Pair<Int, Int>? {
        val starts = net.tripStart[p]; val svc = net.tripService[p]
        var bestTrip = -1; var bestShift = 0; var bestTime = INF
        for ((shift, active) in arrayOf(0 to today, -DAY to yesterday)) {
            var i = lowerBound(starts, ready - off - shift)
            var scanned = 0
            while (i < starts.size && scanned < 400) {
                val sv = svc[i]
                if (sv < active.size && active[sv]) {
                    val t = starts[i] + shift + off
                    if (t < bestTime) { bestTime = t; bestTrip = i; bestShift = shift }
                    break
                }
                i++; scanned++
            }
        }
        return if (bestTrip < 0) null else bestTrip to bestShift
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    private fun trace(
        k0: Int, s0: Int,
        arr: Array<IntArray>, kind: Array<ByteArray>,
        pPattern: Array<IntArray>, pTrip: Array<IntArray>, pShift: Array<IntArray>,
        pBoard: Array<IntArray>, pAlight: Array<IntArray>,
        accessOf: Map<Int, Access>, departSec: Int,
    ): List<PlanLeg>? {
        val legs = ArrayList<PlanLeg>()
        var k = k0; var s = s0
        var guard = 0
        while (guard++ < 50) {
            while (k > 0 && kind[k][s] == NONE) k--
            when (kind[k][s]) {
                ACCESS -> {
                    val a = accessOf[s] ?: return null
                    legs.add(PlanLeg.Walk(-1, s, departSec, departSec + a.walkSec, a.meters))
                    return legs.reversed()
                }
                RIDE -> {
                    val p = pPattern[k][s]; val tr = pTrip[k][s]; val sh = pShift[k][s]
                    val b = pBoard[k][s]; val al = pAlight[k][s]
                    val base = net.tripStart[p][tr] + sh
                    legs.add(PlanLeg.Ride(p, tr, sh, b, al, base + net.patternOffsets[p][b], base + net.patternOffsets[p][al]))
                    s = net.patternStops[p][b]; k -= 1
                }
                TRANSFER -> {
                    val from = pPattern[k][s]; val j = pTrip[k][s]
                    legs.add(PlanLeg.Walk(from, s, arr[k][from], arr[k][s], net.transferMeters[from][j]))
                    s = from
                }
                else -> return null
            }
        }
        return null
    }

    /** Leave as late as possible: walks start just in time for the next ride instead of "now". */
    private fun tighten(j: Journey): Journey {
        val legs = j.legs.toMutableList()
        for (i in legs.indices.reversed()) {
            val w = legs[i] as? PlanLeg.Walk ?: continue
            val next = legs.getOrNull(i + 1) as? PlanLeg.Ride ?: continue
            val dur = w.endSec - w.startSec
            legs[i] = w.copy(startSec = next.startSec - dur, endSec = next.startSec)
        }
        return Journey(legs)
    }
}

/** Several options: the best now, then the best leaving after each found option's first bus. */
fun Raptor.options(
    origins: List<Access>,
    dests: List<Access>,
    departSec: Int,
    today: BooleanArray,
    yesterday: BooleanArray,
    directWalk: PlanLeg.Walk?,
    want: Int = 5,
): List<Journey> {
    val found = LinkedHashMap<String, Journey>()
    var t = departSec
    var tries = 0
    while (found.size < want && tries++ < want + 3) {
        val js = route(origins, dests, t, today, yesterday)
        if (js.isEmpty()) break
        for (j in js) {
            val r = j.firstRide ?: continue
            found.getOrPut("${r.pattern}|${r.trip}|${j.arriveSec}|${j.rides}") { j }
        }
        val nextFirst = js.mapNotNull { it.firstRide?.startSec }.minOrNull() ?: break
        val walk = js.first().legs.first().let { if (it is PlanLeg.Walk) it.endSec - it.startSec else 0 }
        t = maxOf(t + 60, nextFirst - walk + 60)
    }
    var list = found.values
        // Drop options that leave no later and arrive later than another option with no more rides.
        .filter { a -> found.values.none { b -> b !== a && b.departSec >= a.departSec && b.arriveSec <= a.arriveSec && b.rides <= a.rides && (b.departSec > a.departSec || b.arriveSec < a.arriveSec || b.rides < a.rides) } }
        .sortedWith(compareBy({ it.arriveSec }, { it.rides }))
        .take(want)
    if (directWalk != null) {
        val w = Journey(listOf(directWalk))
        list = (listOf(w) + list).sortedBy { it.arriveSec }
    }
    return list
}
