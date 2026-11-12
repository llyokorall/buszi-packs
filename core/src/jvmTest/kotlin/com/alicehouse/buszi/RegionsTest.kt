package com.alicehouse.buszi

import com.alicehouse.buszi.data.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class RegionsTest {
    private fun at(lat: Double, lon: Double) = Regions.find(lat, lon)?.id

    @Test
    fun picksTheRightRegionForRealPlaces() {
        assertEquals("translink", at(49.2827, -123.1207))         // Vancouver downtown
        assertEquals("translink", at(49.1913, -122.8490))         // Surrey
        assertEquals("translink", at(49.1042, -122.6604))         // Langley
        assertEquals("bct-fraser-valley", at(49.0504, -122.3045)) // Abbotsford
        assertEquals("bct-fraser-valley", at(49.1579, -121.9515)) // Chilliwack
        assertEquals("bct-victoria", at(48.4284, -123.3656))
        assertEquals("bct-cowichan", at(48.7787, -123.7079))      // Duncan
        assertEquals("bct-nanaimo", at(49.1659, -123.9401))
        assertEquals("bct-kelowna", at(49.8863, -119.4966))
        assertEquals("bct-vernon", at(50.2671, -119.2720))
        assertEquals("bct-penticton", at(49.4991, -119.5937))
        assertEquals("bct-kamloops", at(50.6745, -120.3273))
        assertEquals("bct-whistler", at(50.1163, -122.9574))
        assertEquals("bct-squamish", at(49.7016, -123.1558))
        assertEquals("bct-prince-george", at(53.9171, -122.7497))
        assertEquals("edmonton", at(53.5444, -113.4909))
        assertEquals("toronto-ttc", at(43.6532, -79.3832))
        assertEquals("montreal-stm", at(45.5019, -73.5674))
        assertEquals("quebec-rtc", at(46.8139, -71.2080))
        assertEquals("ottawa", at(45.4215, -75.6972))
        assertEquals("calgary", at(51.0447, -114.0719))
        assertEquals("winnipeg", at(49.8951, -97.1384))
        assertEquals("halifax", at(44.6488, -63.5752))
        assertEquals("hamilton", at(43.2557, -79.8711))
        assertEquals("brampton", at(43.6855, -79.7599))
        assertEquals("boston-mbta", at(42.3555, -71.0565))
        assertEquals("seattle-kcm", at(47.6062, -122.3321))
        assertEquals("chicago-cta", at(41.8781, -87.6298))
        assertEquals("denver-rtd", at(39.7392, -104.9903))
        assertNull(at(40.7128, -74.0060))                          // New York: not supported yet
        assertEquals("durham", at(43.8971, -78.8658))              // Oshawa
        assertEquals("toronto-ttc", at(43.7764, -79.2318))         // Scarborough Town Centre
        assertEquals("london-ltc", at(42.9849, -81.2453))
        assertEquals("regina", at(50.4452, -104.6189))
        assertEquals("saskatoon", at(52.1332, -106.6700))
        assertEquals("kingston", at(44.2312, -76.4860))
        assertEquals("lethbridge", at(49.6956, -112.8451))
        assertEquals("pittsburgh-prt", at(40.4406, -79.9959))
        assertEquals("atlanta-marta", at(33.7490, -84.3880))
        assertEquals("miami-dade", at(25.7617, -80.1918))
        assertEquals("fort-worth-trinity", at(32.7555, -97.3308))
        assertEquals("waterloo-grt", at(43.4516, -80.4925))        // Kitchener
        assertEquals("guelph", at(43.5448, -80.2482))
        assertEquals("bart", at(37.7955, -122.3937))               // Ferry Building: BART first, the ferry is secondary
        assertEquals("sf-bay-ferry", at(38.1000, -122.2570))       // Vallejo terminal, outside BART's area
        // Rivers: boxes overlap, the shapes decide.
        assertEquals("ottawa", at(45.4236, -75.7009))              // Parliament Hill
        assertEquals("ottawa", at(45.4285, -75.6915))              // ByWard Market
        assertEquals("ottawa", at(45.4700, -75.5200))              // Orléans
        assertEquals("gatineau-sto", at(45.4280, -75.7130))        // Hull
        assertEquals("gatineau-sto", at(45.3950, -75.8450))        // Aylmer
        assertEquals("montreal-stm", at(45.5017, -73.5673))        // downtown
        assertEquals("montreal-stm", at(45.6000, -73.5100))        // Mercier-Est
        assertEquals("montreal-stm", at(45.6000, -73.6300))        // Montréal-Nord (exo box)
        assertEquals("montreal-stm", at(45.6500, -73.4900))        // Pointe-aux-Trembles
        assertEquals("longueuil-rtl", at(45.5245, -73.5218))       // Longueuil metro terminal
        assertEquals("longueuil-rtl", at(45.4500, -73.4700))       // Brossard
        assertEquals("exo-terrebonne", at(45.7000, -73.6473))      // Terrebonne
        assertEquals("exo-sorel-varennes", at(46.0400, -73.1150))  // Sorel-Tracy
        assertEquals("bart", at(37.7880, -122.4075))
        assertEquals("san-diego-mts", at(32.7157, -117.1611))
        assertEquals("austin-capmetro", at(30.2672, -97.7431))
        assertEquals("sacramento-sacrt", at(38.5816, -121.4944))
    }

    @Test
    fun regionDataIsConsistent() {
        val ids = Regions.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (r in Regions.ALL) {
            ZoneId.of(r.timeZone)
            assert(r.contains(r.defaultLat, r.defaultLon)) { "${r.id} center outside its area" }
            assert(r.core[0] >= r.bbox[0] - 1e-9 && r.core[2] <= r.bbox[2] + 1e-9) { "${r.id} core outside bbox" }
            assert(r.staticGtfsUrl.startsWith("https://")) { r.id }
        }
    }

    @Test
    fun linkedRegionsChainAcrossVancouverIsland() {
        val nanaimo = Regions.byId("bct-nanaimo")!!
        val victoria = Regions.byId("bct-victoria")!!
        assertEquals(listOf("bct-nanaimo", "bct-cowichan", "bct-victoria"), Regions.chain(nanaimo, victoria)?.map { it.id })
        assertEquals(listOf("bct-nanaimo"), Regions.chain(nanaimo, nanaimo)?.map { it.id })
        assertNull(Regions.chain(nanaimo, Regions.byId("translink")!!)) // ferry only, not in the data
        assert(victoria in Regions.reachable(nanaimo))
        assertEquals(nanaimo, Regions.reachable(nanaimo).first())
    }

    @Test
    fun linkedRegionsShareATimeZone() {
        for (r in Regions.ALL) for (l in Regions.linked(r)) {
            assertEquals("${r.id} / ${l.id}", r.timeZone, l.timeZone)
            assert(r in Regions.linked(l))
        }
        // every link names real regions
        assertEquals(134, Regions.ALL.sumOf { Regions.linked(it).size })
    }
}
