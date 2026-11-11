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
}
