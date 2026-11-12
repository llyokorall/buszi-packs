package com.alicehouse.buszi

import com.alicehouse.buszi.data.ShapeCodec
import com.alicehouse.buszi.data.Walking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapesTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val lat = doubleArrayOf(49.16396, 49.16012, 49.15001, 48.77503)
        val lon = doubleArrayOf(-123.93181, -123.93511, -123.92001, -123.7016)
        val back = ShapeCodec.decode(ShapeCodec.encode(lat, lon))
        assertEquals(4, back.size)
        back.forEachIndexed { i, (a, o) ->
            assertEquals(lat[i], a, 1e-5); assertEquals(lon[i], o, 1e-5)
        }
    }

    @Test
    fun simplifyDropsPointsOnAStraightRoad() {
        val lat = DoubleArray(101) { 49.0 + it * 0.0001 }
        val lon = DoubleArray(101) { -123.0 }
        assertEquals(listOf(0, 100), ShapeCodec.simplify(lat, lon).toList())
        lon[50] = -123.001 // a ~73 m bend must stay
        assertTrue(50 in ShapeCodec.simplify(lat, lon).toList())
    }

    /** An L-shaped road: north 1 km, then east 1 km. Stops sit 10 m beside it. */
    private val road = listOf(49.0 to -123.0, 49.009 to -123.0, 49.009 to -122.9863)

    @Test
    fun slicesBetweenStopsFollowingTheCorner() {
        val path = ShapeCodec.slice(road, listOf(49.004 to -123.00014, 49.00909 to -122.993))!!
        assertTrue(path.any { it == 49.009 to -123.0 }, "keeps the corner")
        assertEquals(49.004, path.first().first, 1e-4)
        assertEquals(-122.993, path.last().second, 1e-4)
    }

    @Test
    fun outAndBackRoadTakesTheRightPass() {
        // Out north 1 km and back south on the same road: a stop near the middle on the way back.
        val outBack = listOf(49.0 to -123.0, 49.009 to -123.0, 49.0 to -123.00005)
        val path = ShapeCodec.slice(outBack, listOf(49.008 to -123.0, 49.004 to -123.0))!!
        // must go up to the terminus and come back, not jump straight down
        assertTrue(path.any { it.first > 49.0089 })
        var len = 0.0
        path.zipWithNext().forEach { (a, b) -> len += Walking.meters(a.first, a.second, b.first, b.second) }
        assertTrue(len in 600.0..700.0, "length $len")
    }

    @Test
    fun noPathWhenStopsAreFarFromTheShape() {
        assertNull(ShapeCodec.slice(road, listOf(49.004 to -123.0, 49.5 to -123.5)))
    }
}
