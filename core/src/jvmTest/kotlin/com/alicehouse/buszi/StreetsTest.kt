package com.alicehouse.buszi

import com.alicehouse.buszi.data.StreetLine
import com.alicehouse.buszi.data.StreetMode
import com.alicehouse.buszi.data.StreetRouter
import com.alicehouse.buszi.data.Tiles
import com.alicehouse.buszi.data.pathMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreetsTest {
    private fun line(cls: String, vararg pts: Pair<Double, Double>, oneway: Int = 0) =
        StreetLine(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), cls, oneway)

    // An L of streets: west–east along 49.0, then north along -122.99. A footpath cuts the corner.
    private val lShape = listOf(
        line("minor", 49.0 to -123.0, 49.0 to -122.995, 49.0 to -122.99),
        line("minor", 49.0 to -122.99, 49.005 to -122.99, 49.01 to -122.99),
    )

    @Test
    fun shortensStreetNamesForLabels() {
        assertEquals("Terminal Ave N", com.alicehouse.buszi.data.shortStreetName("Terminal Avenue North"))
        assertEquals("Wentworth St", com.alicehouse.buszi.data.shortStreetName("Wentworth Street"))
        assertEquals("Avenue Rd", com.alicehouse.buszi.data.shortStreetName("Avenue Road"))
        assertEquals("North Rd", com.alicehouse.buszi.data.shortStreetName("North Road"))
        assertEquals("Esplanade", com.alicehouse.buszi.data.shortStreetName("Esplanade"))
    }

    @Test
    fun followsStreetsAroundTheCorner() {
        val r = StreetRouter(lShape, StreetMode.WALK)
        val p = assertNotNull(r.route(49.0001, -123.0, 49.01, -122.9901))
        assertTrue(p.any { kotlin.math.abs(it.first - 49.0) < 1e-6 && kotlin.math.abs(it.second + 122.99) < 1e-6 }, "goes via the corner")
        // ~730 m east + ~1110 m north, not the ~1320 m straight line
        assertTrue(pathMeters(p) > 1700)
    }

    @Test
    fun walkersUseFootpathsBusesDont() {
        val withPath = lShape + line("path", 49.0 to -123.0, 49.01 to -122.99)
        val walk = assertNotNull(StreetRouter(withPath, StreetMode.WALK).route(49.0, -123.0, 49.01, -122.99))
        assertTrue(pathMeters(walk) < 1400)
        val bus = assertNotNull(StreetRouter(withPath, StreetMode.BUS).route(49.0, -123.0, 49.01, -122.99))
        assertTrue(pathMeters(bus) > 1700)
    }

    @Test
    fun busRespectsOneWay() {
        // Only a one-way street pointing west: a bus can't go east on it.
        val r = StreetRouter(listOf(line("minor", 49.0 to -122.99, 49.0 to -123.0, oneway = 1)), StreetMode.BUS)
        assertNull(r.route(49.0, -123.0, 49.0, -122.99))
        assertNotNull(r.route(49.0, -122.99, 49.0, -123.0))
    }

    @Test
    fun closesSmallGapAtTJunction() {
        // A side street that stops ~2.5 m short of the main street, with no shared point.
        val lines = listOf(
            line("minor", 49.0 to -123.0, 49.0 to -122.99),
            line("minor", 49.01 to -122.995, 49.0000225 to -122.995),
        )
        val p = assertNotNull(StreetRouter(lines, StreetMode.WALK).route(49.01, -122.995, 49.0, -122.999))
        assertTrue(pathMeters(p) < 1500, "uses the junction, got ${pathMeters(p)}")
    }

    @Test
    fun shortcutAcrossOpenGroundButNotThroughBuildings() {
        // Street along the south edge only; destination 150 m north of it in a parking lot.
        val street = listOf(line("minor", 49.0 to -123.01, 49.0 to -122.99))
        val open = assertNotNull(StreetRouter(street, StreetMode.WALK).route(49.0, -123.0, 49.00135, -123.0))
        assertTrue(pathMeters(open) < 200, "straight across the lot, got ${pathMeters(open)}")
        // A building in between: the straight line is blocked, so the walk goes around it.
        val box = doubleArrayOf(49.0003, -123.0006, 49.0003, -122.9994, 49.001, -122.9994, 49.001, -123.0006)
        val around = StreetRouter(street, StreetMode.WALK, listOf(box)).route(49.0, -123.0, 49.00135, -123.0)
        assertTrue(around == null || around.none { it.first in 49.0004..49.0009 && it.second in -123.0005..-122.9995 })
    }

    @Test
    fun describesPointFromLabels() {
        val tile = com.alicehouse.buszi.data.StreetTile(
            emptyList(), emptyList(),
            points = listOf(
                com.alicehouse.buszi.data.NamedPoint(49.0001, -123.0, "120", isPoi = false),
                com.alicehouse.buszi.data.NamedPoint(49.0005, -123.0, "Corner Cafe", isPoi = true),
            ),
            names = listOf(com.alicehouse.buszi.data.NamedLine(doubleArrayOf(49.0, 49.0), doubleArrayOf(-123.001, -122.999), "Main St")),
        )
        assertEquals("120 Main St" to null, com.alicehouse.buszi.data.describePoint(listOf(tile), 49.0001, -123.0001))
        assertEquals("Corner Cafe", com.alicehouse.buszi.data.describePoint(listOf(tile), 49.0005, -123.0)?.first)
        assertEquals("Main St" to null, com.alicehouse.buszi.data.describePoint(listOf(tile), 49.0003, -122.9992))
    }

    @Test
    fun bikePrefersBikePathAndGivesTurns() {
        // Main road straight east; a cycleway north then east then south around it (a bit longer).
        val lines = listOf(
            line("primary", 49.0 to -123.0, 49.0 to -122.99),
            StreetLine(doubleArrayOf(49.0, 49.002, 49.002, 49.0), doubleArrayOf(-123.0, -123.0, -122.99, -122.99), "path", 0, "cycleway", ""),
        )
        val p = assertNotNull(StreetRouter(lines, StreetMode.BIKE).route(49.0, -123.0, 49.0, -122.99))
        assertTrue(p.any { it.first > 49.001 }, "uses the cycleway")
        val names = listOf(
            com.alicehouse.buszi.data.NamedLine(doubleArrayOf(49.0, 49.002), doubleArrayOf(-123.0, -123.0), "North Path"),
            com.alicehouse.buszi.data.NamedLine(doubleArrayOf(49.002, 49.002), doubleArrayOf(-123.0, -122.99), "Top Trail"),
            com.alicehouse.buszi.data.NamedLine(doubleArrayOf(49.002, 49.0), doubleArrayOf(-122.99, -122.99), "South Path"),
        )
        val d = com.alicehouse.buszi.data.directionsFor(p, lines, names)
        assertEquals(listOf("North Path", "Top Trail", "South Path"), d.steps.map { it.name })
        assertEquals(com.alicehouse.buszi.data.Turn.RIGHT, d.steps[1].turn)
        assertEquals(com.alicehouse.buszi.data.Turn.RIGHT, d.steps[2].turn)
        assertTrue(d.metersBy(com.alicehouse.buszi.data.RoadKind.BIKE_PATH) > d.meters * 0.9)
    }

    @Test
    fun spursAreRemoved() {
        val p = listOf(49.0 to -123.0, 49.0 to -122.999, 49.001 to -122.999, 49.0 to -122.999, 49.0 to -122.998)
        val q = com.alicehouse.buszi.data.dropLoops(p)
        assertEquals(listOf(49.0 to -123.0, 49.0 to -122.999, 49.0 to -122.998), q)
    }

    @Test
    fun tileMath() {
        // Vancouver City Hall at z14
        assertEquals(2588, Tiles.x(-123.1139))
        assertEquals(5608, Tiles.y(49.2606))
    }
}

class RealTileTest {
    /** Set BUSZI_TILE to a downloaded z14/2588/5608 OpenFreeMap tile to run this check. */
    @Test
    fun decodesVancouverTile() {
        val path = System.getenv("BUSZI_TILE") ?: return
        val lines = com.alicehouse.buszi.data.MvtStreets.decode(java.io.File(path).readBytes(), 14, 2588, 5608)
        val classes = lines.groupingBy { it.cls }.eachCount()
        println("lines=${lines.size} classes=$classes")
        assertTrue(lines.size > 100)
        assertTrue(lines.all { l -> l.lat.all { it in 49.20..49.30 } && l.lon.all { it in -123.16..-123.06 } })
        val r = StreetRouter(lines, StreetMode.WALK)
        // Vancouver City Hall -> Broadway-City Hall station area, a few blocks
        val p = assertNotNull(r.route(49.2606, -123.1139, 49.2633, -123.1146))
        println("walk points=${p.size} meters=${pathMeters(p)}")
    }
}

class NanaimoWalkTest {
    /** Network check (set BUSZI_NET=1): Metral Dr at Aulds Rd -> Woodgrove should be a short walk north. */
    @Test
    fun metralToWoodgrove() {
        if (System.getenv("BUSZI_NET") != "1") return
        fun get(u: String): ByteArray {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Accept-Encoding", "gzip")
            val raw = c.inputStream.readBytes()
            return if (c.contentEncoding == "gzip") java.util.zip.GZIPInputStream(raw.inputStream()).readBytes() else raw
        }
        val tpl = Regex("\"tiles\":\\[\"([^\"]+)\"").find(get("https://tiles.openfreemap.org/planet").decodeToString())!!.groupValues[1]
        val a = 49.23293 to -124.04845
        val b = 49.2367 to -124.0505
        val keys = Tiles.around(a.first, a.second, b.first, b.second, 300.0)
        val tiles = keys.map { (x, y) ->
            com.alicehouse.buszi.data.MvtStreets.decodeTile(get(tpl.replace("{z}", "14").replace("{x}", "$x").replace("{y}", "$y")), 14, x, y)
        }
        val lines = tiles.flatMap { it.lines }
        val p = assertNotNull(StreetRouter(lines, StreetMode.WALK, tiles.flatMap { it.buildings }).route(a.first, a.second, b.first, b.second))
        println("tiles=${keys.size} meters=${pathMeters(p)} straight=${com.alicehouse.buszi.data.Walking.meters(a.first, a.second, b.first, b.second).toInt()}")
        assertTrue(pathMeters(p) < 900, "walk should be short, got ${pathMeters(p)}")
    }
}

class SeattleWalkTest {
    /** Network check (BUSZI_NET=1): Denny Way & 5th Ave N -> Space Needle must not cut through buildings. */
    @Test
    fun dennyToSpaceNeedle() {
        if (System.getenv("BUSZI_NET") != "1") return
        fun get(u: String): ByteArray {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Accept-Encoding", "gzip")
            val raw = c.inputStream.readBytes()
            return if (c.contentEncoding == "gzip") java.util.zip.GZIPInputStream(raw.inputStream()).readBytes() else raw
        }
        val tpl = Regex("\"tiles\":\\[\"([^\"]+)\"").find(get("https://tiles.openfreemap.org/planet").decodeToString())!!.groupValues[1]
        val a = 47.6185 to -122.3478
        val b = 47.6205 to -122.3493
        val keys = Tiles.around(a.first, a.second, b.first, b.second, 300.0)
        val tiles = keys.map { (x, y) ->
            com.alicehouse.buszi.data.MvtStreets.decodeTile(get(tpl.replace("{z}", "14").replace("{x}", "$x").replace("{y}", "$y")), 14, x, y)
        }
        val buildings = tiles.flatMap { it.buildings }
        val p = assertNotNull(StreetRouter(tiles.flatMap { it.lines }, StreetMode.WALK, buildings).route(a.first, a.second, b.first, b.second))
        println("buildings=${buildings.size} meters=${pathMeters(p)}")
        fun inside(r: DoubleArray, la: Double, lo: Double): Boolean {
            var ins = false; val n = r.size / 2; var j = n - 1
            for (i in 0 until n) {
                val yi = r[2 * i]; val xi = r[2 * i + 1]; val yj = r[2 * j]; val xj = r[2 * j + 1]
                if ((yi > la) != (yj > la) && lo < (xj - xi) * (la - yi) / (yj - yi) + xi) ins = !ins
                j = i
            }
            return ins
        }
        // sample along the path: no point (except near the destination) inside a building
        for (i in 1 until p.size) for (k in 1..9) {
            val la = p[i - 1].first + (p[i].first - p[i - 1].first) * k / 10
            val lo = p[i - 1].second + (p[i].second - p[i - 1].second) * k / 10
            if (com.alicehouse.buszi.data.Walking.meters(la, lo, b.first, b.second) < 60) continue // mapped footways around the destination may pass under it
            assertTrue(buildings.none { inside(it, la, lo) }, "path goes through a building at $la,$lo")
        }
    }
}

class PoiSearchTest {
    private fun tile(vararg pts: com.alicehouse.buszi.data.NamedPoint) =
        com.alicehouse.buszi.data.StreetTile(emptyList(), emptyList(), pts.toList(), emptyList())

    @Test
    fun matchesIgnoringSpacesCaseAndAccents() {
        assertEquals("bestbuy", com.alicehouse.buszi.data.searchKey("Best Buy"))
        assertEquals("cafemontreal", com.alicehouse.buszi.data.searchKey("Café Montréal!"))
        val t = tile(
            com.alicehouse.buszi.data.NamedPoint(49.2366, -124.0490, "Best Buy", true),
            com.alicehouse.buszi.data.NamedPoint(49.2367, -124.0491, "Best Buy", true), // same shop drawn twice
            com.alicehouse.buszi.data.NamedPoint(49.1660, -123.9400, "The Best Buy Liquor", true),
            com.alicehouse.buszi.data.NamedPoint(49.1660, -123.9401, "12", false), // house number, never a result
        )
        val r = com.alicehouse.buszi.data.searchPois(listOf(t), "bestbuy", 49.1659, -123.9401)
        assertEquals(listOf("Best Buy", "The Best Buy Liquor"), r.map { it.name }) // starts-with first, duplicate dropped
        assertTrue(com.alicehouse.buszi.data.searchPois(listOf(t), "be", 49.0, -123.0).isEmpty()) // too short
    }

    /** Network check (BUSZI_NET=1): "bestbuy" near Woodgrove Centre, Nanaimo, from real z14 tiles. */
    @Test
    fun findsBestBuyInNanaimoTiles() {
        if (System.getenv("BUSZI_NET") != "1") return
        fun get(u: String): ByteArray {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Accept-Encoding", "gzip")
            val raw = c.inputStream.readBytes()
            return if (c.contentEncoding == "gzip") java.util.zip.GZIPInputStream(raw.inputStream()).readBytes() else raw
        }
        val tpl = Regex("\"tiles\":\\[\"([^\"]+)\"").find(get("https://tiles.openfreemap.org/planet").decodeToString())!!.groupValues[1]
        val here = 49.2330 to -124.0480 // Metral Dr near Woodgrove; Best Buy is ~4 km away on Rutherford Rd
        val keys = Tiles.around(here.first, here.second, here.first, here.second, 5_000.0)
        val tiles = keys.map { (x, y) ->
            com.alicehouse.buszi.data.MvtStreets.decodeTile(get(tpl.replace("{z}", "14").replace("{x}", "$x").replace("{y}", "$y")), 14, x, y)
        }
        val r = com.alicehouse.buszi.data.searchPois(tiles, "bestbuy", here.first, here.second)
        println("tiles=${keys.size} pois=${tiles.sumOf { t -> t.points.count { it.isPoi } }} found=${r.map { "${it.name} ${it.lat},${it.lon}" }}")
        assertTrue(r.any { it.name.contains("Best Buy", ignoreCase = true) })
    }
}
