package com.alicehouse.buszi

import com.alicehouse.buszi.data.Access
import com.alicehouse.buszi.data.MergedNetwork
import com.alicehouse.buszi.data.PlanLeg
import com.alicehouse.buszi.data.PlanPartData
import com.alicehouse.buszi.data.PlanNetwork
import com.alicehouse.buszi.data.Raptor
import com.alicehouse.buszi.data.options
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {
    // 0 —A→ 1 —A→ 2 —B→ 3, stops ~1.1 km apart (no walking transfers between them); 4 is 100 m from 2.
    private fun net() = PlanNetwork(
        stopLat = doubleArrayOf(49.0, 49.01, 49.02, 49.03, 49.0209),
        stopLon = doubleArrayOf(-123.0, -123.0, -123.0, -123.0, -123.0),
        patternStops = arrayOf(intArrayOf(0, 1, 2), intArrayOf(2, 3), intArrayOf(4, 3)),
        patternOffsets = arrayOf(intArrayOf(0, 300, 600), intArrayOf(0, 300), intArrayOf(0, 200)),
        patternBoard = arrayOf(booleanArrayOf(true, true, true), booleanArrayOf(true, true), booleanArrayOf(true, true)),
        tripStart = arrayOf(intArrayOf(8 * 3600, 8 * 3600 + 1800), intArrayOf(8 * 3600 + 1200, 8 * 3600 + 3000), intArrayOf(9 * 3600)),
        tripService = arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0)),
    )

    private val today = booleanArrayOf(true, false)
    private val none = booleanArrayOf(false, false)

    @Test
    fun oneChange() {
        val r = Raptor(net())
        val js = r.route(listOf(Access(0, 60, 50)), listOf(Access(3, 60, 50)), 7 * 3600 + 3300, today, none)
        assertEquals(1, js.size)
        val j = js.first()
        assertEquals(2, j.rides)
        val rides = j.legs.filterIsInstance<PlanLeg.Ride>()
        assertEquals(8 * 3600, rides[0].startSec)
        assertEquals(8 * 3600 + 600, rides[0].endSec)
        assertEquals(8 * 3600 + 1500, rides[1].endSec)
        assertEquals(8 * 3600 + 1560, j.arriveSec)
        // leave just in time for the first bus
        assertEquals(8 * 3600 - 60, j.departSec)
    }

    @Test
    fun skipsInactiveService() {
        val r = Raptor(net())
        // after 8:20 the only B trip left is service 1, which doesn't run today
        val js = r.route(listOf(Access(0, 60, 50)), listOf(Access(3, 60, 50)), 8 * 3600 + 1000, today, none)
        // A 8:30 -> stop 2 at 8:40, walk to 4, ride C 9:00 -> 3 at 9:03:20
        val j = js.single()
        assertTrue(j.legs.any { it is PlanLeg.Walk && it.fromStop == 2 && it.toStop == 4 })
        assertEquals(9 * 3600 + 200 + 60, j.arriveSec)
    }

    @Test
    fun yesterdaysLateTrips() {
        val r = Raptor(net())
        // pattern A trip at "32:00" of yesterday == 08:00 today
        val n = PlanNetwork(
            doubleArrayOf(49.0, 49.01), doubleArrayOf(-123.0, -123.0),
            arrayOf(intArrayOf(0, 1)), arrayOf(intArrayOf(0, 300)), arrayOf(booleanArrayOf(true, true)),
            arrayOf(intArrayOf(32 * 3600)), arrayOf(intArrayOf(1)),
        )
        val js = Raptor(n).route(listOf(Access(0, 0, 0)), listOf(Access(1, 0, 0)), 7 * 3600, none, booleanArrayOf(false, true))
        assertEquals(8 * 3600 + 300, js.single().arriveSec)
        assertTrue(r.options(listOf(Access(0, 60, 50)), listOf(Access(3, 60, 50)), 7 * 3600, today, none, null).isNotEmpty())
    }

    /**
     * Two regions like Nanaimo and Cowichan Valley: region A's bus ends at a mall, region B's bus
     * leaves from a stop 40 m away. Both regions number their services from 0.
     */
    @Test
    fun changesBetweenMergedRegions() {
        val a = PlanPartData(
            stopLat = doubleArrayOf(49.16, 48.7750), stopLon = doubleArrayOf(-123.93, -123.7016),
            patternStops = arrayOf(intArrayOf(0, 1)), patternOffsets = arrayOf(intArrayOf(0, 3000)),
            patternBoard = arrayOf(booleanArrayOf(true, true)),
            tripStart = arrayOf(intArrayOf(5 * 3600 + 600)), tripService = arrayOf(intArrayOf(0)),
            maxService = 1,
        )
        val b = PlanPartData(
            stopLat = doubleArrayOf(48.77465, 48.418), stopLon = doubleArrayOf(-123.7016, -123.368),
            patternStops = arrayOf(intArrayOf(0, 1)), patternOffsets = arrayOf(intArrayOf(0, 4200)),
            patternBoard = arrayOf(booleanArrayOf(true, true)),
            // 06:15 runs on B's service 0, 06:30 on B's service 1
            tripStart = arrayOf(intArrayOf(6 * 3600 + 900, 6 * 3600 + 1800)), tripService = arrayOf(intArrayOf(0, 1)),
            maxService = 1,
        )
        val m = MergedNetwork(listOf(a, b))
        assertEquals(listOf(0, 0, 1, 1), m.stopPart.toList())
        assertEquals(listOf(0, 2), m.serviceBase.toList())
        assertEquals(listOf(2, 3), m.net.patternStops[1].toList())
        assertTrue(m.net.transferTo[1].contains(2)) // the two mall stops are linked on foot

        // A's service 0 and only B's service 1 run today (the 06:15 doesn't). Service 5 of A is
        // unknown (no trips) and must not switch on any of B's services.
        val today = m.active(listOf(setOf(0, 5), setOf(1)))
        assertEquals(listOf(true, false, false, true), today.toList())
        val js = Raptor(m.net).route(listOf(Access(0, 0, 0)), listOf(Access(3, 0, 0)), 5 * 3600, today, BooleanArray(today.size))
        val j = js.single()
        assertEquals(2, j.rides)
        val second = j.legs.filterIsInstance<PlanLeg.Ride>()[1]
        assertEquals(1, m.patternPart[second.pattern])
        assertEquals(6 * 3600 + 1800, second.startSec)
        assertEquals(1, m.net.tripService[second.pattern][second.trip] - m.serviceBase[1])
    }
}
