package com.alicehouse.buszi.data

/**
 * A region = one downloadable timetable (one static GTFS feed) plus its live feeds.
 *
 * LICENCE NOTES (checked 2026-11-11; keep in sync with docs/COMPLIANCE.md):
 *  - Edmonton: City of Edmonton Open Data Terms of Use v2.1. Commercial use, modification and
 *    redistribution allowed; show the terms link; no endorsement; no City logos.
 *  - BC Transit: https://www.bctransit.com/open-data/terms-of-use. Licence to "use, reproduce,
 *    and redistribute"; must say BC Transit is the source; no BC Transit marks; data "as is".
 *    Modification/hosting a transformed copy is not expressly granted -> [packAllowed] = false
 *    until BC Transit confirms in writing.
 *  - TransLink: GTFS Static Data terms. Same "use, reproduce, and redistribute" licence, must
 *    disclose commercial use to TransLink, and must show [requiredNotice] prominently, verbatim.
 *    Real-time needs an API key limited to 1,000 requests/day, which cannot serve many app
 *    users directly -> live data only if a key is configured (see [apiKeyName]).
 */
data class Region(
    val id: String,
    val displayName: String,
    /** Province / state code, e.g. "BC". */
    val area: String,
    val country: String,
    val timeZone: String,
    val defaultLat: Double,
    val defaultLon: Double,
    /** minLat, minLon, maxLat, maxLon of all stops. */
    val bbox: DoubleArray,
    /** Same, without the 3 % outermost stops: where the service really is. Used to pick a region. */
    val core: DoubleArray = bbox,
    val staticGtfsUrl: String,
    /** May contain "{apikey}". Null = no live data. */
    val tripUpdatesUrl: String?,
    val vehiclePositionsUrl: String?,
    val alertsUrl: String?,
    /** Name of the API key the live URLs need (app supplies it), or null. */
    val apiKeyName: String? = null,
    val dataCredit: String,
    val dataTermsLabel: String,
    val dataTermsUrl: String,
    val disclaimer: String,
    /** Short credit line (region picker). Shown on the home screen only via [requiredNotice]. */
    val shortCredit: String,
    /** Text the provider requires to be shown prominently, verbatim (static data). */
    val requiredNotice: String? = null,
    /** Extra verbatim text required when the live (API) feeds are used. */
    val liveNotice: String? = null,
    /** True only when the licence clearly allows hosting a transformed copy (prebuilt pack). */
    val packAllowed: Boolean = false,
    /** GTFS route types not imported (licence or data-quality reasons). */
    val excludeRouteTypes: Set<Int> = emptySet(),
    /**
     * Where the service really is, as a polygon of (lat, lon), when a box can't separate it from a
     * neighbour across a river (Gatineau / Ottawa, Longueuil / Montréal). Replaces [core] for picking.
     */
    val coreShape: List<Pair<Double, Double>>? = null,
    /** Picked only where no main region covers the point (suburban feeds overlapping a city, e.g. exo). */
    val secondary: Boolean = false,
) {
    fun contains(lat: Double, lon: Double, marginDeg: Double = 0.05, box: DoubleArray = bbox): Boolean =
        lat >= box[0] - marginDeg && lat <= box[2] + marginDeg &&
            lon >= box[1] - marginDeg && lon <= box[3] + marginDeg

    /** True where the service really is: inside [coreShape] if set, else inside [core] (+ a small margin). */
    fun inCore(lat: Double, lon: Double): Boolean {
        val shape = coreShape ?: return contains(lat, lon, 0.02, core)
        var inside = false
        var j = shape.size - 1
        for (i in shape.indices) {
            val (yi, xi) = shape[i]; val (yj, xj) = shape[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    val bboxArea: Double get() = (bbox[2] - bbox[0]) * (bbox[3] - bbox[1])
    val coreArea: Double get() = (core[2] - core[0]) * (core[3] - core[1])

    override fun equals(other: Any?) = other is Region && other.id == id
    override fun hashCode() = id.hashCode()
}

object Regions {
    private const val BCT_STATIC = "https://bct.tmix.se/Tmix.Cap.TdExport.WebApi/gtfs/?operatorIds="
    private const val BCT_RT = "https://bct.tmix.se/gtfs-realtime/"

    private fun bcTransit(
        operatorId: Int,
        id: String,
        name: String,
        bbox: DoubleArray,
        core: DoubleArray,
        center: Pair<Double, Double>,
        tz: String = "America/Vancouver",
    ) = Region(
        id = "bct-$id",
        displayName = name,
        area = "BC",
        country = "CA",
        timeZone = tz,
        defaultLat = center.first,
        defaultLon = center.second,
        bbox = bbox,
        core = core,
        staticGtfsUrl = BCT_STATIC + operatorId,
        tripUpdatesUrl = "${BCT_RT}tripupdates.pb?operatorIds=$operatorId",
        vehiclePositionsUrl = "${BCT_RT}vehicleupdates.pb?operatorIds=$operatorId",
        alertsUrl = "${BCT_RT}alerts.pb?operatorIds=$operatorId",
        dataCredit = "Transit schedules and real-time data: BC Transit open data. BC Transit is the source of this data.",
        dataTermsLabel = "BC Transit Open Data Terms of Use",
        dataTermsUrl = "https://www.bctransit.com/open-data/terms-of-use",
        disclaimer = "This app is independent. It is not affiliated with, sponsored by, or endorsed by BC Transit " +
            "or its local government partners. Data is provided \"as is\" and may be inaccurate. Always check official sources.",
        shortCredit = "Data source: BC Transit. Not affiliated with BC Transit.",
    )

    private fun b(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = doubleArrayOf(minLat, minLon, maxLat, maxLon)

    val EDMONTON = Region(
        id = "edmonton",
        displayName = "Edmonton",
        area = "AB",
        country = "CA",
        timeZone = "America/Edmonton",
        defaultLat = 53.5444,
        defaultLon = -113.4909,
        bbox = b(53.250, -113.935, 53.714, -113.203),
        core = b(53.406, -113.672, 53.648, -113.286),
        staticGtfsUrl = "https://gtfs.edmonton.ca/TMGTFSRealTimeWebService/GTFS/GTFS.zip",
        tripUpdatesUrl = "https://gtfs.edmonton.ca/TMGTFSRealTimeWebService/TripUpdate/TripUpdates.pb",
        vehiclePositionsUrl = "https://gtfs.edmonton.ca/TMGTFSRealTimeWebService/Vehicle/VehiclePositions.pb",
        alertsUrl = "https://gtfs.edmonton.ca/TMGTFSRealTimeWebService/Alert/Alerts.pb",
        dataCredit = "Transit schedules and real-time data are publicly available from the " +
            "City of Edmonton (Edmonton Transit Service) under the City of Edmonton Open Data Terms of Use.",
        dataTermsLabel = "City of Edmonton Open Data Terms of Use",
        dataTermsUrl = "https://data.edmonton.ca/stories/s/City-of-Edmonton-Open-Data-Terms-of-Use/msh8-if28/",
        disclaimer = "This app is independent. It is not affiliated with, sponsored by, or endorsed by " +
            "the City of Edmonton, Edmonton Transit Service, St. Albert Transit or Strathcona County Transit. " +
            "Times are estimates provided \"as is\" and may be inaccurate. Always check official sources.",
        shortCredit = "Data: City of Edmonton Open Data. Not affiliated with the City.",
        packAllowed = true,
    )

    private const val TRANSLINK_NOTICE =
        "Route and arrival data used in this product or service is provided by permission of TransLink. " +
            "TransLink assumes no responsibility for the accuracy or currency of the Data used in this product or service."

    /** Open API Terms of Use (developer.translink.ca), wording confirmed by the user 2026-11-11. */
    private const val TRANSLINK_API_NOTICE =
        "Some of the data used in this product or service is provided by permission of TransLink. " +
            "TransLink assumes no responsibility for the accuracy or currency of the Data used in this product or service."

    val METRO_VANCOUVER = Region(
        id = "translink",
        displayName = "Metro Vancouver",
        area = "BC",
        country = "CA",
        timeZone = "America/Vancouver",
        defaultLat = 49.2827,
        defaultLon = -123.1207,
        bbox = b(49.004, -123.423, 49.474, -122.304),
        core = b(49.031, -123.189, 49.344, -122.585),
        staticGtfsUrl = "https://gtfs-static.translink.ca/gtfs/google_transit.zip",
        tripUpdatesUrl = "https://gtfsapi.translink.ca/v3/gtfsrealtime?apikey={apikey}",
        vehiclePositionsUrl = "https://gtfsapi.translink.ca/v3/gtfsposition?apikey={apikey}",
        alertsUrl = "https://gtfsapi.translink.ca/v3/gtfsalerts?apikey={apikey}",
        apiKeyName = "translink",
        dataCredit = TRANSLINK_NOTICE,
        dataTermsLabel = "TransLink GTFS data terms",
        dataTermsUrl = "https://www.translink.ca/about-us/doing-business-with-translink/app-developer-resources/gtfs/gtfs-data",
        disclaimer = "This app is independent. It is not affiliated with, sponsored by, or endorsed by TransLink. " +
            "Always check official sources.",
        shortCredit = TRANSLINK_NOTICE,
        requiredNotice = TRANSLINK_NOTICE,
        liveNotice = TRANSLINK_API_NOTICE,
    )

    /** BC Transit systems, verified against the live feeds on 2026-11-11 (operator ids). */
    val BC_TRANSIT = listOf(
        bcTransit(48, "victoria", "Victoria", b(48.337, -123.795, 48.698, -123.286), b(48.372, -123.718, 48.656, -123.308), 48.4284 to -123.3656),
        bcTransit(47, "kelowna", "Kelowna", b(49.752, -119.790, 50.074, -119.347), b(49.781, -119.732, 50.038, -119.371), 49.8863 to -119.4966),
        bcTransit(46, "kamloops", "Kamloops", b(50.634, -120.423, 50.860, -120.079), b(50.640, -120.409, 50.775, -120.158), 50.6745 to -120.3273),
        bcTransit(41, "nanaimo", "Nanaimo", b(48.775, -124.726, 49.464, -123.702), b(49.102, -124.463, 49.354, -123.871), 49.1659 to -123.9401),
        bcTransit(22, "prince-george", "Prince George", b(53.817, -127.167, 54.785, -122.738), b(53.850, -126.650, 54.396, -122.742), 53.9171 to -122.7497),
        bcTransit(13, "fraser-valley", "Fraser Valley (Abbotsford, Mission, Chilliwack)", b(49.004, -122.895, 49.383, -121.420), b(49.035, -122.381, 49.239, -121.763), 49.0504 to -122.3045),
        bcTransit(45, "comox-valley", "Comox Valley", b(49.464, -125.148, 49.873, -124.726), b(49.584, -125.118, 49.834, -124.887), 49.6866 to -124.9936),
        bcTransit(44, "whistler", "Whistler", b(50.077, -123.044, 50.159, -122.927), b(50.080, -123.040, 50.153, -122.934), 50.1163 to -122.9574),
        bcTransit(43, "squamish", "Squamish", b(49.696, -123.158, 49.774, -123.099), b(49.698, -123.157, 49.768, -123.105), 49.7016 to -123.1558),
        bcTransit(14, "vernon", "Vernon & Shuswap", b(49.940, -119.488, 50.932, -118.963), b(49.969, -119.390, 50.753, -119.166), 50.2671 to -119.2720),
        bcTransit(15, "penticton", "South Okanagan-Similkameen (Penticton)", b(49.020, -120.510, 49.887, -119.448), b(49.182, -119.723, 49.603, -119.539), 49.4991 to -119.5937),
        bcTransit(10, "cowichan", "Cowichan Valley", b(48.416, -124.219, 48.998, -123.364), b(48.445, -124.050, 48.993, -123.374), 48.7787 to -123.7079),
        bcTransit(12, "campbell-river", "Campbell River", b(49.870, -125.286, 50.064, -125.129), b(49.922, -125.277, 50.047, -125.185), 50.0244 to -125.2475),
        bcTransit(11, "port-alberni", "Port Alberni", b(49.218, -124.850, 49.277, -124.783), b(49.224, -124.845, 49.273, -124.788), 49.2339 to -124.8055),
        bcTransit(20, "west-kootenay", "West Kootenay (Nelson, Castlegar, Trail)", b(49.071, -118.140, 50.297, -116.898), b(49.077, -117.802, 49.961, -116.962), 49.4928 to -117.2948),
        bcTransit(21, "east-kootenay", "East Kootenay (Cranbrook, Kimberley, Elk Valley)", b(49.490, -116.135, 50.698, -114.871), b(49.495, -116.032, 50.516, -114.889), 49.5097 to -115.7688, tz = "America/Edmonton"),
        bcTransit(18, "sunshine-coast", "Sunshine Coast", b(49.392, -123.912, 49.514, -123.475), b(49.394, -123.905, 49.494, -123.480), 49.4741 to -123.7545),
        bcTransit(29, "powell-river", "qathet (Powell River)", b(49.654, -124.761, 49.981, -124.178), b(49.763, -124.645, 49.901, -124.348), 49.8352 to -124.5247),
        bcTransit(23, "prince-rupert", "Prince Rupert", b(54.218, -130.353, 54.331, -130.267), b(54.224, -130.350, 54.329, -130.288), 54.3150 to -130.3208),
        bcTransit(26, "terrace-kitimat", "Terrace, Kitimat & Hazeltons", b(53.966, -128.710, 55.352, -127.167), b(54.047, -128.667, 55.260, -127.625), 54.5182 to -128.6032),
        bcTransit(31, "smithers", "Smithers", b(54.682, -127.333, 55.019, -127.047), b(54.682, -127.333, 55.015, -127.051), 54.7804 to -127.1743),
        bcTransit(32, "quesnel", "Quesnel", b(52.939, -123.584, 53.106, -121.515), b(52.957, -122.528, 53.102, -121.569), 52.9784 to -122.4927),
        bcTransit(33, "williams-lake", "Williams Lake", b(52.108, -122.167, 52.168, -121.992), b(52.109, -122.163, 52.150, -122.093), 52.1294 to -122.1383),
        bcTransit(27, "dawson-creek", "Dawson Creek", b(55.739, -120.257, 55.776, -120.210), b(55.740, -120.254, 55.773, -120.214), 55.7596 to -120.2353, tz = "America/Dawson_Creek"),
        bcTransit(28, "fort-st-john", "Fort St. John", b(56.232, -120.874, 56.267, -120.803), b(56.233, -120.867, 56.262, -120.808), 56.2465 to -120.8476, tz = "America/Dawson_Creek"),
        bcTransit(36, "revelstoke", "Revelstoke", b(50.955, -118.231, 51.021, -118.161), b(50.976, -118.225, 51.016, -118.164), 50.9981 to -118.1957),
        bcTransit(37, "merritt", "Merritt", b(50.102, -120.883, 50.177, -120.749), b(50.102, -120.882, 50.165, -120.764), 50.1113 to -120.7862),
        bcTransit(39, "salt-spring", "Salt Spring Island", b(48.769, -123.572, 48.915, -123.446), b(48.769, -123.572, 48.891, -123.450), 48.8544 to -123.5084),
        bcTransit(50, "pemberton", "Pemberton Valley", b(50.114, -122.961, 50.333, -122.656), b(50.116, -122.961, 50.331, -122.661), 50.3192 to -122.8027),
        bcTransit(51, "west-coast", "West Coast (Tofino, Ucluelet)", b(48.938, -125.909, 49.154, -125.525), b(48.938, -125.908, 49.152, -125.527), 49.1530 to -125.9066),
        bcTransit(17, "mount-waddington", "Mount Waddington (Port Hardy)", b(50.571, -127.587, 50.731, -127.011), b(50.579, -127.575, 50.728, -127.070), 50.7231 to -127.4969),
        bcTransit(16, "creston", "Creston Valley", b(49.088, -116.554, 49.182, -116.466), b(49.088, -116.554, 49.181, -116.483), 49.0955 to -116.5135),
        bcTransit(34, "100-mile-house", "100 Mile House", b(51.631, -121.379, 51.743, -121.290), b(51.631, -121.379, 51.728, -121.291), 51.6418 to -121.2940),
        bcTransit(35, "clearwater", "Clearwater", b(50.655, -120.372, 51.679, -119.728), b(50.655, -120.372, 51.650, -119.913), 51.6500 to -120.0382),
        bcTransit(38, "ashcroft", "Ashcroft, Cache Creek & Clinton", b(50.714, -121.584, 51.093, -121.279), b(50.714, -121.584, 50.811, -121.279), 50.7254 to -121.2810),
    )

    private fun indep(agency: String) =
        "This app is independent. It is not affiliated with, sponsored by, or endorsed by $agency. " +
            "Data is provided \"as is\" and may be inaccurate. Always check official sources."

    /**
     * Other Canadian cities (licences checked 2026-11-11, see docs/COMPLIANCE.md).
     * Left out on purpose: MiWay (website terms appear non-commercial), York Region Transit
     * (licence must be accepted through a registration form first).
     */
    val CANADA_OTHER = listOf(
        Region(
            id = "toronto-ttc", displayName = "Toronto (TTC)", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 43.6532, defaultLon = -79.3832,
            bbox = b(43.592, -79.650, 43.910, -79.123), core = b(43.625, -79.590, 43.824, -79.200),
            staticGtfsUrl = "https://opendata.toronto.ca/toronto.transit.commission/ttc-routes-and-schedules/OpenData_TTC_Schedules.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Contains information licensed under the Open Government Licence – Toronto.",
            dataTermsLabel = "Open Government Licence – Toronto", dataTermsUrl = "https://open.toronto.ca/open-data-licence/",
            disclaimer = indep("the City of Toronto or the Toronto Transit Commission"),
            shortCredit = "Contains information licensed under the Open Government Licence – Toronto.",
            packAllowed = true,
        ),
        Region(
            id = "montreal-stm", displayName = "Montréal (STM buses)", area = "QC", country = "CA", timeZone = "America/Toronto",
            defaultLat = 45.5019, defaultLon = -73.5674,
            bbox = b(45.403, -73.966, 45.701, -73.481), core = b(45.429, -73.871, 45.654, -73.508),
            staticGtfsUrl = "https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Source: Société de transport de Montréal (STM), licence CC BY 4.0. " +
                "Metro lines are not shown: STM's metro schedules are indicative and may not be used in apps.",
            dataTermsLabel = "STM open data terms (CC BY 4.0)", dataTermsUrl = "https://www.stm.info/fr/a-propos/developpeurs",
            disclaimer = indep("the Société de transport de Montréal"),
            shortCredit = "Source: STM (CC BY 4.0). Not affiliated with the STM.",
            packAllowed = true,
            excludeRouteTypes = setOf(1),
        ),
        Region(
            id = "quebec-rtc", displayName = "Québec City (RTC)", area = "QC", country = "CA", timeZone = "America/Toronto",
            defaultLat = 46.8139, defaultLon = -71.2080,
            bbox = b(46.735, -71.472, 46.938, -71.149), core = b(46.748, -71.426, 46.910, -71.178),
            staticGtfsUrl = "https://cdn.rtcquebec.ca/Site_Internet/DonneesOuvertes/googletransit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Application intégrant les Informations publiques du Réseau de transport de la Capitale, mises à jour le {updated}.",
            dataTermsLabel = "RTC open data licence", dataTermsUrl = "https://www.rtcquebec.ca/donnees-ouvertes",
            disclaimer = indep("the Réseau de transport de la Capitale"),
            shortCredit = "Application intégrant les Informations publiques du Réseau de transport de la Capitale, mises à jour le {updated}.",
            requiredNotice = "Application intégrant les Informations publiques du Réseau de transport de la Capitale, mises à jour le {updated}.",
            packAllowed = true,
        ),
        Region(
            id = "winnipeg", displayName = "Winnipeg", area = "MB", country = "CA", timeZone = "America/Winnipeg",
            defaultLat = 49.8951, defaultLon = -97.1384,
            bbox = b(49.764, -97.321, 49.969, -96.963), core = b(49.787, -97.300, 49.955, -97.010),
            staticGtfsUrl = "https://gtfs.winnipegtransit.com/google_transit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Data provided by permission of Winnipeg Transit, a department of The City of Winnipeg (Public Domain Dedication and Licence).",
            dataTermsLabel = "Winnipeg Transit data terms", dataTermsUrl = "https://api.winnipegtransit.com/home/terms",
            disclaimer = indep("the City of Winnipeg or Winnipeg Transit"),
            shortCredit = "Data provided by permission of Winnipeg Transit. Not affiliated with the City of Winnipeg.",
            packAllowed = true,
        ),
        Region(
            id = "calgary", displayName = "Calgary", area = "AB", country = "CA", timeZone = "America/Edmonton",
            defaultLat = 51.0447, defaultLon = -114.0719,
            bbox = b(50.853, -114.272, 51.190, -113.822), core = b(50.887, -114.220, 51.167, -113.935),
            staticGtfsUrl = "https://data.calgary.ca/download/npk7-z3bj/application%2Fzip",
            tripUpdatesUrl = "https://data.calgary.ca/download/gs4m-mdc2/application%2Foctet-stream",
            vehiclePositionsUrl = "https://data.calgary.ca/download/am7c-qe3u/application%2Foctet-stream",
            alertsUrl = "https://data.calgary.ca/download/jhgn-ynqj/application%2Foctet-stream",
            dataCredit = "Contains information licensed under the Open Government Licence – City of Calgary.",
            dataTermsLabel = "Open Government Licence – City of Calgary", dataTermsUrl = "https://data.calgary.ca/stories/s/u45n-7awa/",
            disclaimer = indep("the City of Calgary or Calgary Transit"),
            shortCredit = "Contains information licensed under the Open Government Licence – City of Calgary.",
        ),
        Region(
            id = "halifax", displayName = "Halifax", area = "NS", country = "CA", timeZone = "America/Halifax",
            defaultLat = 44.6488, defaultLon = -63.5752,
            bbox = b(44.565, -63.858, 44.886, -63.283), core = b(44.604, -63.723, 44.790, -63.460),
            staticGtfsUrl = "https://gtfs.halifax.ca/static/google_transit.zip",
            tripUpdatesUrl = "https://gtfs.halifax.ca/realtime/TripUpdate/TripUpdates.pb",
            vehiclePositionsUrl = "https://gtfs.halifax.ca/realtime/Vehicle/VehiclePositions.pb",
            alertsUrl = "https://gtfs.halifax.ca/realtime/Alert/Alerts.pb",
            dataCredit = "Contains information licenced under the Open Government Licence—Halifax.",
            dataTermsLabel = "Open Government Licence – Halifax", dataTermsUrl = "https://data-hrm.hub.arcgis.com/pages/open-data-licence",
            disclaimer = indep("Halifax Regional Municipality or Halifax Transit"),
            shortCredit = "Contains information licenced under the Open Government Licence—Halifax.",
        ),
        Region(
            id = "ottawa", displayName = "Ottawa (OC Transpo)", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 45.4215, defaultLon = -75.6972,
            bbox = b(45.130, -76.042, 45.520, -75.343), core = b(45.240, -75.928, 45.481, -75.455),
            staticGtfsUrl = "https://oct-gtfs-emasagcnfmcgeham.z01.azurefd.net/public-access/GTFSExport.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Transit data: OC Transpo / City of Ottawa open data, under the City of Ottawa Open Data Terms of Use.",
            dataTermsLabel = "OC Transpo developer terms", dataTermsUrl = "https://www.octranspo.com/en/plan-your-trip/travel-tools/developers/dev-terms",
            disclaimer = indep("the City of Ottawa or OC Transpo"),
            shortCredit = "Data: OC Transpo / City of Ottawa open data. Not affiliated with OC Transpo.",
        ),
        Region(
            id = "hamilton", displayName = "Hamilton (HSR)", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 43.2557, defaultLon = -79.8711,
            bbox = b(43.156, -80.128, 43.337, -79.639), core = b(43.189, -79.989, 43.293, -79.714),
            staticGtfsUrl = "https://opendata.hamilton.ca/GTFS-Static/google_transit.zip",
            tripUpdatesUrl = "https://opendata.hamilton.ca/GTFS-RT/GTFS_TripUpdates.pb",
            vehiclePositionsUrl = "https://opendata.hamilton.ca/GTFS-RT/GTFS_VehiclePositions.pb",
            alertsUrl = "https://opendata.hamilton.ca/GTFS-RT/GTFS_ServiceAlerts.pb",
            dataCredit = "Contains public sector Data made available under the City of Hamilton's Open Data Licence.",
            dataTermsLabel = "City of Hamilton Open Data Licence",
            dataTermsUrl = "https://www.hamilton.ca/city-council/data-maps/open-data/open-data-licence-terms-and-conditions",
            disclaimer = indep("the City of Hamilton or the Hamilton Street Railway"),
            shortCredit = "Contains public sector Data made available under the City of Hamilton's Open Data Licence.",
        ),
        Region(
            id = "brampton", displayName = "Brampton", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 43.6855, defaultLon = -79.7599,
            bbox = b(43.548, -79.865, 43.893, -79.528), core = b(43.633, -79.840, 43.794, -79.635),
            staticGtfsUrl = "https://www.arcgis.com/sharing/rest/content/items/a355aabd5a8c490186bdce559c9c75fb/data",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Transit data: City of Brampton, licensed under Creative Commons Attribution (CC BY).",
            dataTermsLabel = "City of Brampton open data", dataTermsUrl = "https://geohub.brampton.ca/pages/licence",
            disclaimer = indep("the City of Brampton or Brampton Transit"),
            shortCredit = "Data: City of Brampton (CC BY). Not affiliated with the City of Brampton.",
        ),
        Region(
            id = "go-transit", displayName = "GO Transit (Greater Toronto)", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 43.6453, defaultLon = -79.3806,
            bbox = b(42.977, -80.976, 44.374, -78.287), core = b(43.152, -80.252, 44.299, -78.691),
            staticGtfsUrl = "https://assets.metrolinx.com/raw/upload/Documents/Metrolinx/Open%20Data/GO-GTFS.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Data used in this product or service is provided with the permission of Metrolinx. " +
                "Metrolinx makes no representations or warranties of any kind about the data.",
            dataTermsLabel = "Metrolinx data terms", dataTermsUrl = "https://www.gotransit.com/en/partner-with-us/software-developers",
            disclaimer = indep("Metrolinx or GO Transit"),
            shortCredit = "Data used in this product or service is provided with the permission of Metrolinx.",
            requiredNotice = "Data used in this product or service is provided with the permission of Metrolinx.",
        ),
        // Added 2026-11-12. Licences read that day (see docs/COMPLIANCE.md). Timetable only: the
        // live feeds' terms were not checked, so they are left out.
        Region(
            id = "durham", displayName = "Durham Region (Oshawa, Pickering, Ajax)", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 43.8971, defaultLon = -78.8658,
            bbox = b(43.774, -79.259, 44.436, -78.580), core = b(43.809, -79.163, 44.260, -78.668),
            staticGtfsUrl = "https://maps.durham.ca/OpenDataGTFS/GTFS_Durham_TXT.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Contains public sector Information made available under The Regional Municipality of Durham's Open Data Licence.",
            dataTermsLabel = "Region of Durham Open Data Licence",
            dataTermsUrl = "https://www.durham.ca/en/regional-government/resources/Documents/OpenDataLicenceAgreement.pdf",
            disclaimer = indep("the Regional Municipality of Durham or Durham Region Transit"),
            shortCredit = "Contains public sector Information made available under The Regional Municipality of Durham's Open Data Licence.",
        ),
        Region(
            id = "london-ltc", displayName = "London (LTC)", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 42.9849, defaultLon = -81.2453,
            bbox = b(42.905, -81.363, 43.051, -81.132), core = b(42.925, -81.335, 43.041, -81.153),
            staticGtfsUrl = "https://www.londontransit.ca/gtfsfeed/google_transit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Transit data available from the London Transit Commission under LTC's Open Data Terms of Use.",
            dataTermsLabel = "LTC Open Data Terms of Use",
            dataTermsUrl = "https://www.londontransit.ca/open-data/ltcs-open-data-terms-of-use/",
            disclaimer = indep("the London Transit Commission"),
            shortCredit = "Data: London Transit Commission open data. Not affiliated with LTC.",
            packAllowed = true,
        ),
        Region(
            id = "regina", displayName = "Regina", area = "SK", country = "CA", timeZone = "America/Regina",
            defaultLat = 50.4452, defaultLon = -104.6189,
            bbox = b(50.401, -104.751, 50.509, -104.504), core = b(50.403, -104.687, 50.501, -104.524),
            staticGtfsUrl = "https://opengis.regina.ca/reginagtfs/google_transit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Contains information licensed under the Open Government Licence – City of Regina.",
            dataTermsLabel = "Open Government Licence – City of Regina",
            dataTermsUrl = "https://www.regina.ca/city-government/open-data/open-government-licence",
            disclaimer = indep("the City of Regina or Regina Transit"),
            shortCredit = "Contains information licensed under the Open Government Licence – City of Regina.",
            packAllowed = true,
        ),
        Region(
            id = "waterloo-grt", displayName = "Waterloo Region (GRT)", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 43.4516, defaultLon = -80.4925,
            bbox = b(43.336, -80.717, 43.600, -80.272), core = b(43.344, -80.591, 43.528, -80.285),
            staticGtfsUrl = "https://webapps.regionofwaterloo.ca/api/grt-routes/api/staticfeeds/1",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Contains information provided by the Regional Municipality of Waterloo under licence (Region of Waterloo Open Data Licence).",
            dataTermsLabel = "Region of Waterloo Open Data Licence",
            dataTermsUrl = "https://www.regionofwaterloo.ca/government-and-council/transparency-and-accountability/open-data/",
            disclaimer = indep("the Regional Municipality of Waterloo or Grand River Transit"),
            shortCredit = "Contains information provided by the Regional Municipality of Waterloo under licence.",
            packAllowed = true,
        ),
        Region(
            id = "guelph", displayName = "Guelph", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 43.5448, defaultLon = -80.2482,
            bbox = b(43.483, -80.322, 43.581, -80.175), core = b(43.490, -80.309, 43.577, -80.182),
            // The City links its scheduled feed from explore.guelph.ca/pages/transit-gtfs-data to this file.
            staticGtfsUrl = "https://drive.google.com/uc?export=download&id=1SsCgQ0agO_EONavJoOuBPMlbcu2VcUs4",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Contains information licensed under the Open Government Licence – City of Guelph.",
            dataTermsLabel = "Open Government Licence – City of Guelph",
            dataTermsUrl = "https://gismaps.guelph.ca/Images/OpenDataLicenceVersion2.pdf",
            disclaimer = indep("the City of Guelph or Guelph Transit"),
            shortCredit = "Contains information licensed under the Open Government Licence – City of Guelph.",
            requiredNotice = "Contains information licensed under the Open Government Licence – City of Guelph.",
            packAllowed = true,
        ),
        Region(
            id = "gatineau-sto", displayName = "Gatineau (STO)", area = "QC", country = "CA", timeZone = "America/Toronto",
            defaultLat = 45.4765, defaultLon = -75.7013,
            bbox = b(45.381, -75.890, 45.593, -75.391), core = b(45.386, -75.868, 45.585, -75.407),
            staticGtfsUrl = "https://contenu.sto.ca/GTFS/GTFS.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Source : Société de transport de l'Outaouais (STO), données ouvertes.",
            dataTermsLabel = "Conditions d'utilisation des données ouvertes de la STO",
            dataTermsUrl = "https://sto.ca/sto/affaires/conditions-dutilisation-donnees-ouvertes-et-cle-api-de-la-sto/",
            disclaimer = indep("the Société de transport de l'Outaouais"),
            shortCredit = "Source : Société de transport de l'Outaouais (STO). Not affiliated with the STO.",
            requiredNotice = "Source : Société de transport de l'Outaouais (STO).",
            // North of the Ottawa River (mid-river line drawn by hand from the map, 2026-11-12).
            coreShape = listOf(
                45.370 to -75.900, 45.385 to -75.850, 45.395 to -75.800, 45.405 to -75.765, 45.418 to -75.730,
                45.428 to -75.705, 45.435 to -75.690, 45.445 to -75.675, 45.460 to -75.640, 45.475 to -75.580,
                45.490 to -75.500, 45.500 to -75.400, 45.620 to -75.400, 45.620 to -75.900,
            ),
        ),
        Region(
            id = "longueuil-rtl", displayName = "Longueuil (RTL)", area = "QC", country = "CA", timeZone = "America/Toronto",
            defaultLat = 45.5312, defaultLon = -73.5185,
            bbox = b(45.384, -73.578, 45.637, -73.317), core = b(45.432, -73.519, 45.614, -73.343),
            staticGtfsUrl = "https://www.rtl-longueuil.qc.ca/transit/latestfeed/RTL.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Source : Réseau de transport de Longueuil (RTL), données ouvertes.",
            dataTermsLabel = "Conditions d'utilisation des données GTFS du RTL",
            dataTermsUrl = "https://www.rtl-longueuil.qc.ca/donnees-ouvertes",
            disclaimer = indep("the Réseau de transport de Longueuil"),
            shortCredit = "Source : Réseau de transport de Longueuil (RTL). Not affiliated with the RTL.",
            requiredNotice = "Source : Réseau de transport de Longueuil (RTL).",
            // South shore of the St. Lawrence (mid-river line drawn by hand from the map, 2026-11-12).
            coreShape = listOf(
                45.360 to -73.620, 45.420 to -73.570, 45.462 to -73.520, 45.490 to -73.525, 45.510 to -73.535,
                45.530 to -73.530, 45.550 to -73.520, 45.575 to -73.500, 45.600 to -73.480, 45.625 to -73.465,
                45.650 to -73.455, 45.670 to -73.430, 45.670 to -73.300, 45.360 to -73.300,
            ),
        ),
        exo("trains", "Montréal commuter trains (exo)", b(45.361, -74.141, 45.773, -73.178), b(45.361, -74.141, 45.773, -73.178), 45.4995 to -73.5664),
        exo("citla", "exo – Laurentides", b(45.460, -74.207, 45.842, -73.653), b(45.495, -74.102, 45.812, -73.707), 45.6384 to -73.8424, id = "exo-laurentides"),
        exo("mrclm", "exo – Terrebonne-Mascouche", b(45.554, -73.844, 45.802, -73.498), b(45.582, -73.815, 45.791, -73.508), 45.7000 to -73.6473, id = "exo-terrebonne"),
        exo("mrclasso", "exo – L'Assomption", b(45.589, -73.540, 45.901, -73.277), b(45.612, -73.521, 45.890, -73.294), 45.8274 to -73.4269, id = "exo-lassomption"),
        exo("citsv", "exo – Sorel-Varennes", b(45.524, -73.538, 46.038, -73.088), b(45.541, -73.509, 46.026, -73.107), 45.6837 to -73.4380, id = "exo-sorel-varennes"),
        exo("omitsju", "exo – Sainte-Julie", b(45.437, -73.522, 45.633, -72.960), b(45.524, -73.496, 45.632, -72.964), 45.5835 to -73.3291, id = "exo-sainte-julie"),
        exo("citvr", "exo – Vallée du Richelieu", b(45.437, -73.539, 45.641, -72.945), b(45.508, -73.519, 45.626, -72.950), 45.5422 to -73.2116, id = "exo-vallee-richelieu"),
        exo("citcrc", "exo – Chambly-Richelieu-Carignan", b(45.298, -73.522, 45.541, -73.140), b(45.315, -73.498, 45.515, -73.146), 45.4486 to -73.2878, id = "exo-chambly"),
        exo("lrrs", "exo – Le Richelain / Roussillon", b(45.348, -73.649, 45.541, -73.388), b(45.353, -73.626, 45.444, -73.417), 45.3874 to -73.5132, id = "exo-roussillon"),
        exo("citso", "exo – Sud-Ouest", b(45.235, -74.132, 45.498, -73.566), b(45.247, -73.965, 45.446, -73.605), 45.3600 to -73.7500, id = "exo-sud-ouest"),
        exo("citpi", "exo – La Presqu'île", b(45.351, -74.315, 45.487, -73.874), b(45.354, -74.306, 45.480, -73.878), 45.4089 to -74.0230, id = "exo-presquile"),
        Region(
            id = "kingston", displayName = "Kingston", area = "ON", country = "CA", timeZone = "America/Toronto",
            defaultLat = 44.2312, defaultLon = -76.4860,
            bbox = b(44.218, -76.667, 44.279, -76.439), core = b(44.219, -76.654, 44.273, -76.447),
            staticGtfsUrl = "https://api.cityofkingston.ca/gtfs/gtfs.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            // Open Data Kingston "About": openly available, "no leasing or licensing agreements required".
            dataCredit = "Transit data: City of Kingston, Open Data Kingston.",
            dataTermsLabel = "Open Data Kingston", dataTermsUrl = "https://opendatakingston.cityofkingston.ca/",
            disclaimer = indep("the City of Kingston or Kingston Transit"),
            shortCredit = "Data: City of Kingston open data. Not affiliated with Kingston Transit.",
        ),
        Region(
            id = "lethbridge", displayName = "Lethbridge", area = "AB", country = "CA", timeZone = "America/Edmonton",
            defaultLat = 49.6956, defaultLon = -112.8451,
            bbox = b(49.652, -112.913, 49.739, -112.781), core = b(49.654, -112.905, 49.738, -112.784),
            staticGtfsUrl = "https://gis.lethbridge.ca/OpenData/DataSets/GTFS_Transit_Data.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Transit data: City of Lethbridge Open Data, under the City of Lethbridge Open Data License.",
            dataTermsLabel = "City of Lethbridge Open Data License",
            dataTermsUrl = "https://opendata.lethbridge.ca/pages/open-data-license",
            disclaimer = indep("the City of Lethbridge or Lethbridge Transit"),
            shortCredit = "Data: City of Lethbridge Open Data. Not affiliated with the City of Lethbridge.",
            packAllowed = true,
        ),
        // saskatoon.ca/services-residents/connect-your-city/open-data: data "is free to use, re-use and
        // redistribute without any legal, technological or social restriction"; Saskatoon Transit:
        // "anyone can use the data to create their own applications" (read 2026-11-12).
        Region(
            id = "saskatoon", displayName = "Saskatoon", area = "SK", country = "CA", timeZone = "America/Regina",
            defaultLat = 52.1332, defaultLon = -106.6700,
            bbox = b(52.079, -106.761, 52.202, -106.532), core = b(52.085, -106.747, 52.192, -106.551),
            staticGtfsUrl = "https://apps5.saskatoon.ca/data/gtfs/google_transit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Transit data: City of Saskatoon Open Data (Saskatoon Transit).",
            dataTermsLabel = "City of Saskatoon Open Data",
            dataTermsUrl = "https://www.saskatoon.ca/services-residents/connect-your-city/open-data",
            disclaimer = indep("the City of Saskatoon or Saskatoon Transit"),
            shortCredit = "Data: City of Saskatoon Open Data. Not affiliated with Saskatoon Transit.",
        ),
    )

    /**
     * United States, first batch (licences checked 2026-11-11, see docs/COMPLIANCE.md).
     * Left out on purpose: MTA New York (terms forbid modifying data; ad-supported apps may need a
     * paid licence), SEPTA / TriMet / Metro Transit Minneapolis (commercial use or licence unclear).
     */
    val USA = listOf(
        Region(
            id = "boston-mbta", displayName = "Boston (MBTA)", area = "MA", country = "US", timeZone = "America/New_York",
            defaultLat = 42.3555, defaultLon = -71.0565,
            bbox = b(41.581, -71.848, 42.798, -70.277), core = b(42.166, -71.251, 42.521, -70.893),
            staticGtfsUrl = "https://cdn.mbta.com/MBTA_GTFS.zip",
            tripUpdatesUrl = "https://cdn.mbta.com/realtime/TripUpdates.pb",
            vehiclePositionsUrl = "https://cdn.mbta.com/realtime/VehiclePositions.pb",
            alertsUrl = "https://cdn.mbta.com/realtime/Alerts.pb",
            dataCredit = "Data provided by the Massachusetts Department of Transportation (MassDOT), the provider of the Data, " +
                "under the MassDOT Developers License Agreement.",
            dataTermsLabel = "MassDOT Developers License Agreement",
            dataTermsUrl = "https://cdn.mbta.com/sites/default/files/2023-08/mbta-massdot-develop-license-agreement.pdf",
            disclaimer = indep("MassDOT or the MBTA"),
            shortCredit = "Data provided by MassDOT. Not affiliated with MassDOT or the MBTA.",
        ),
        Region(
            id = "seattle-kcm", displayName = "Seattle (King County Metro)", area = "WA", country = "US", timeZone = "America/Los_Angeles",
            defaultLat = 47.6062, defaultLon = -122.3321,
            bbox = b(47.189, -122.507, 47.866, -121.786), core = b(47.297, -122.394, 47.760, -122.035),
            // metro.kingcounty.gov serves an incomplete TLS chain that Android rejects; this is King
            // County Metro's same feed as published for the Puget Sound OneBusAway region.
            staticGtfsUrl = "https://gtfs.sound.obaweb.org/prod/1_gtfs.zip",
            tripUpdatesUrl = "https://s3.amazonaws.com/kcm-alerts-realtime-prod/tripupdates.pb",
            vehiclePositionsUrl = "https://s3.amazonaws.com/kcm-alerts-realtime-prod/vehiclepositions.pb",
            alertsUrl = "https://s3.amazonaws.com/kcm-alerts-realtime-prod/alerts.pb",
            dataCredit = "Transit scheduling, geographic, and real-time data provided by permission of King County.",
            dataTermsLabel = "King County Transit Data Terms of Use",
            dataTermsUrl = "https://kingcounty.gov/depts/transportation/metro/travel-options/bus/app-center/developer-resources.aspx",
            disclaimer = indep("King County or King County Metro"),
            shortCredit = "Transit scheduling, geographic, and real-time data provided by permission of King County.",
            requiredNotice = "Transit scheduling, geographic, and real-time data provided by permission of King County.",
            packAllowed = true,
        ),
        Region(
            id = "chicago-cta", displayName = "Chicago (CTA)", area = "IL", country = "US", timeZone = "America/Chicago",
            defaultLat = 41.8781, defaultLon = -87.6298,
            bbox = b(41.644, -87.904, 42.073, -87.526), core = b(41.692, -87.805, 42.018, -87.565),
            staticGtfsUrl = "https://www.transitchicago.com/downloads/sch_data/google_transit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Data provided by Chicago Transit Authority.",
            dataTermsLabel = "CTA developer license agreement", dataTermsUrl = "https://www.transitchicago.com/developers/terms/",
            disclaimer = indep("the Chicago Transit Authority"),
            shortCredit = "Data provided by Chicago Transit Authority. Not affiliated with the CTA.",
            packAllowed = true,
        ),
        Region(
            id = "denver-rtd", displayName = "Denver (RTD)", area = "CO", country = "US", timeZone = "America/Denver",
            defaultLat = 39.7392, defaultLon = -104.9903,
            bbox = b(38.835, -108.564, 40.590, -104.670), core = b(39.576, -105.270, 40.147, -104.772),
            staticGtfsUrl = "https://www.rtd-denver.com/files/gtfs/google_transit.zip",
            tripUpdatesUrl = "https://open-data.rtd-denver.com/files/gtfs-rt/rtd/TripUpdate.pb",
            vehiclePositionsUrl = "https://open-data.rtd-denver.com/files/gtfs-rt/rtd/VehiclePosition.pb",
            alertsUrl = "https://open-data.rtd-denver.com/files/gtfs-rt/rtd/Alerts.pb",
            dataCredit = "Transit data: Regional Transportation District (RTD), under the RTD GTFS License Agreement.",
            dataTermsLabel = "RTD GTFS License Agreement",
            dataTermsUrl = "https://rtd-denver.com/open-records/open-spatial-information/gtfs-realtime-license-agreement",
            disclaimer = "This app is not endorsed by, sponsored by or affiliated with RTD. " +
                "Data is provided \"as is\" and may be inaccurate. Always check official sources.",
            shortCredit = "Transit data: RTD. Not endorsed by, sponsored by or affiliated with RTD.",
        ),
        // Added 2026-11-12. These four use the same "use, reproduce, and redistribute" licence as
        // BC Transit: no prebuilt packs (modification not expressly granted), no agency marks,
        // timetable only (live feeds not checked).
        usLicensed(
            id = "bart", name = "San Francisco Bay Area (BART)", area = "CA", tz = "America/Los_Angeles",
            center = 37.7880 to -122.4075,
            bbox = b(37.368, -122.469, 38.019, -121.780), core = b(37.368, -122.469, 38.019, -121.780),
            url = "https://www.bart.gov/dev/schedules/google_transit.zip",
            agency = "Bay Area Rapid Transit (BART)", termsLabel = "BART Developer License Agreement",
            termsUrl = "https://www.bart.gov/schedules/developers/developer-license-agreement",
        ),
        usLicensed(
            id = "san-diego-mts", name = "San Diego (MTS)", area = "CA", tz = "America/Los_Angeles",
            center = 32.7157 to -117.1611,
            bbox = b(32.543, -117.278, 33.257, -116.184), core = b(32.558, -117.254, 33.038, -116.521),
            url = "https://www.sdmts.com/google_transit_files/google_transit.zip",
            agency = "San Diego Metropolitan Transit System (MTS)", termsLabel = "MTS Developer License Agreement",
            termsUrl = "https://www.sdmts.com/business-center-developers/terms-and-conditions",
        ),
        usLicensed(
            id = "austin-capmetro", name = "Austin (CapMetro)", area = "TX", tz = "America/Chicago",
            center = 30.2672 to -97.7431,
            bbox = b(30.147, -97.991, 30.586, -97.372), core = b(30.171, -97.879, 30.517, -97.637),
            url = "https://data.texas.gov/download/r4v4-vz24/application/zip",
            agency = "Capital Metropolitan Transportation Authority (CapMetro)", termsLabel = "CapMetro Open Data License Agreement",
            termsUrl = "https://www.capmetro.org/metrolabs/",
        ),
        usLicensed(
            id = "sacramento-sacrt", name = "Sacramento (SacRT)", area = "CA", tz = "America/Los_Angeles",
            center = 38.5816 to -121.4944,
            bbox = b(38.393, -121.764, 38.723, -121.092), core = b(38.463, -121.541, 38.708, -121.124),
            url = "https://apps.sacrt.com/gtfs/srtd/google_transit.zip",
            agency = "Sacramento Regional Transit District (SacRT)", termsLabel = "SacRT data license agreement",
            termsUrl = "https://www.sacrt.com/transit-data-portal/",
        ),
        Region(
            id = "sf-bay-ferry", displayName = "San Francisco Bay Ferry", area = "CA", country = "US", timeZone = "America/Los_Angeles",
            defaultLat = 37.7955, defaultLon = -122.3937,
            bbox = b(37.663, -122.412, 38.101, -122.257), core = b(37.663, -122.412, 38.101, -122.257),
            staticGtfsUrl = "https://gtfs.sanfranciscobayferry.com/gtfs.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = "Schedule data: San Francisco Bay Ferry, licensed under the Open Data Commons Attribution License (ODC-BY).",
            dataTermsLabel = "ODC-BY (San Francisco Bay Ferry developer resources)",
            dataTermsUrl = "https://www.sfbayferry.com/developers/",
            disclaimer = indep("San Francisco Bay Ferry (WETA)"),
            shortCredit = "Data: San Francisco Bay Ferry (ODC-BY). Not affiliated with San Francisco Bay Ferry.",
            requiredNotice = "Schedule data: San Francisco Bay Ferry, ODC-BY.",
            packAllowed = true,
            secondary = true, // a handful of terminals; its box covers downtown San Francisco
        ),
        Region(
            id = "pittsburgh-prt", displayName = "Pittsburgh", area = "PA", country = "US", timeZone = "America/New_York",
            defaultLat = 40.4406, defaultLon = -79.9959,
            bbox = b(40.273, -80.249, 40.668, -79.705), core = b(40.316, -80.182, 40.587, -79.750),
            staticGtfsUrl = "https://rideprt.org/developerresources/gtfs.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            // Developer License Agreement: data reproduced accurately (format may change); this notice is required verbatim.
            dataCredit = PRT_NOTICE,
            dataTermsLabel = "PRT Developer License Agreement",
            dataTermsUrl = "https://rideprt.org/policies/developer-license-agreement/",
            disclaimer = indep("Pittsburgh Regional Transit (Port Authority of Allegheny County)"),
            shortCredit = PRT_NOTICE,
            requiredNotice = PRT_NOTICE,
        ),
        Region(
            id = "atlanta-marta", displayName = "Atlanta", area = "GA", country = "US", timeZone = "America/New_York",
            defaultLat = 33.7490, defaultLon = -84.3880,
            bbox = b(33.432, -84.670, 34.106, -84.083), core = b(33.529, -84.575, 34.062, -84.145),
            staticGtfsUrl = "https://itsmarta.com/google_transit_feed/google_transit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            // App Developer Resources invites apps "using our open schedule and real-time data";
            // the agency's marks and logos may not be used (so the region is named after the city).
            dataCredit = "Schedule data: Metropolitan Atlanta Rapid Transit Authority open data (App Developer Resources).",
            dataTermsLabel = "Metropolitan Atlanta Rapid Transit Authority – App Developer Resources",
            dataTermsUrl = "https://itsmarta.com/app-developer-resources.aspx",
            disclaimer = indep("the Metropolitan Atlanta Rapid Transit Authority"),
            shortCredit = "Data: Metropolitan Atlanta Rapid Transit Authority. Not affiliated with or endorsed by the Authority.",
        ),
        Region(
            id = "miami-dade", displayName = "Miami-Dade", area = "FL", country = "US", timeZone = "America/New_York",
            defaultLat = 25.7617, defaultLon = -80.1918,
            bbox = b(24.716, -81.076, 25.985, -80.119), core = b(25.467, -80.477, 25.963, -80.122),
            staticGtfsUrl = "https://www.miamidade.gov/transit/googletransit/current/google_transit.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            // Published on the County's "Transit Open Data Feeds" page with no licence or restriction
            // (only the website disclaimer); Florida public records (see docs/COMPLIANCE.md).
            dataCredit = "Schedule data: Miami-Dade County Department of Transportation and Public Works, Transit Open Data Feeds.",
            dataTermsLabel = "Miami-Dade County Transit Open Data Feeds",
            dataTermsUrl = "https://www.miamidade.gov/global/transportation/open-data-feeds.page",
            disclaimer = indep("Miami-Dade County"),
            shortCredit = "Data: Miami-Dade County open data. Not affiliated with Miami-Dade County.",
        ),
        Region(
            id = "fort-worth-trinity", displayName = "Fort Worth (Trinity Metro)", area = "TX", country = "US", timeZone = "America/Chicago",
            defaultLat = 32.7555, defaultLon = -97.3308,
            bbox = b(32.563, -97.479, 32.958, -97.038), core = b(32.635, -97.469, 32.906, -97.069),
            staticGtfsUrl = "https://gtfsdata.ridetm.org/gtfs/fwtatransitdata.zip",
            tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
            dataCredit = TRINITY_NOTICE,
            dataTermsLabel = "Trinity Metro GTFS data legal notice",
            dataTermsUrl = "https://ridetrinitymetro.org/gtfs-data/",
            disclaimer = indep("Trinity Metro"),
            shortCredit = TRINITY_NOTICE,
            // "If data is being obtained for public use, a disclaimer must be publicly displayed stating: …"
            requiredNotice = TRINITY_NOTICE,
        ),
    )

    private const val PRT_NOTICE =
        "Reproduced with permission granted by Pittsburgh Regional Transit (PRT). The information has been provided " +
            "by means of a nonexclusive, limited, and revocable license granted by PRT. PRT does not guarantee the " +
            "accuracy, adequacy, completeness, or usefulness of any information. PRT provides this information \"as is\" " +
            "without warranty of any kind, express or implied, including, but not limited to, warranties of " +
            "merchantability or fitness for a particular purpose and assumes no responsibility for anyone's use of the information."

    private const val TRINITY_NOTICE =
        "Data provided by Trinity Metro is not warranted by Trinity Metro for accuracy or completeness and data is subject to change without notice."

    /**
     * One exo feed (Montréal-area commuter trains or a suburban bus sector). All exo GTFS sets are
     * published on Données Québec under CC BY 4.0 (checked 2026-11-12), so packs are allowed.
     */
    private fun exo(
        sector: String, name: String, bbox: DoubleArray, core: DoubleArray, center: Pair<Double, Double>,
        id: String = "exo-$sector",
    ) = Region(
        id = id, displayName = name, area = "QC", country = "CA", timeZone = "America/Toronto",
        defaultLat = center.first, defaultLon = center.second, bbox = bbox, core = core,
        staticGtfsUrl = "https://exo.quebec/uploads/ressources-telechargeables/a-propos/donnees-ouvertes/$sector/google_transit.zip",
        tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
        dataCredit = "Source : exo (Réseau de transport métropolitain), données ouvertes sous licence CC BY 4.0 (Données Québec).",
        dataTermsLabel = "Licence CC BY 4.0 (Données Québec)",
        dataTermsUrl = "https://www.donneesquebec.ca/licence/",
        disclaimer = indep("exo (Réseau de transport métropolitain)"),
        shortCredit = "Source : exo, CC BY 4.0. Not affiliated with exo.",
        requiredNotice = "Source : exo (Réseau de transport métropolitain), CC BY 4.0.",
        packAllowed = true,
        // Sectors' boxes reach into Montréal and Laval; the island stays with the STM.
        secondary = true,
    )

    /** A US agency under a "use, reproduce, and redistribute" developer licence (timetable only). */
    private fun usLicensed(
        id: String, name: String, area: String, tz: String, center: Pair<Double, Double>,
        bbox: DoubleArray, core: DoubleArray, url: String, agency: String, termsLabel: String, termsUrl: String,
    ) = Region(
        id = id, displayName = name, area = area, country = "US", timeZone = tz,
        defaultLat = center.first, defaultLon = center.second, bbox = bbox, core = core,
        staticGtfsUrl = url, tripUpdatesUrl = null, vehiclePositionsUrl = null, alertsUrl = null,
        dataCredit = "Transit schedules: $agency open data, under the $termsLabel. Provided \"as is\".",
        dataTermsLabel = termsLabel, dataTermsUrl = termsUrl,
        disclaimer = indep(agency),
        shortCredit = "Data: $agency. Not affiliated with or endorsed by $agency.",
    )

    val ALL: List<Region> = listOf(METRO_VANCOUVER) + BC_TRANSIT + EDMONTON + CANADA_OTHER + USA

    fun byId(id: String?): Region? = ALL.firstOrNull { it.id == id }

    /**
     * Regions whose buses meet, so a trip can continue from one into the other (e.g. Nanaimo's
     * 70 NCX and Cowichan Valley's 66 CVX both stop at Village Green Mall in Duncan).
     * Checked 2026-11-11 against the feeds: every pair has stops within 300 m of each other and
     * the same time zone. Regions whose areas only overlap across water (Nanaimo / Sunshine Coast,
     * Comox Valley / Powell River, Cowichan / Salt Spring, Cowichan / Metro Vancouver) are not linked.
     */
    private val LINKS: List<Pair<String, String>> = listOf(
        "bct-nanaimo" to "bct-cowichan",
        "bct-cowichan" to "bct-victoria",
        "bct-nanaimo" to "bct-comox-valley",
        "bct-comox-valley" to "bct-campbell-river",
        "bct-kelowna" to "bct-vernon",
        "bct-kelowna" to "bct-penticton",
        "bct-kamloops" to "bct-clearwater",
        "bct-prince-george" to "bct-smithers",
        "bct-prince-george" to "bct-terrace-kitimat",
        "bct-terrace-kitimat" to "bct-smithers",
        "bct-whistler" to "bct-pemberton",
        "translink" to "bct-fraser-valley",
        "toronto-ttc" to "go-transit",
        "toronto-ttc" to "brampton",
        "brampton" to "go-transit",
        "hamilton" to "go-transit",
        "durham" to "go-transit",
        "durham" to "toronto-ttc",
        "guelph" to "go-transit",
        "waterloo-grt" to "go-transit",
        "ottawa" to "gatineau-sto",
        // Greater Montréal (checked 2026-11-12, stops within 300 m).
        "montreal-stm" to "longueuil-rtl",
        "montreal-stm" to "exo-trains",
        "montreal-stm" to "exo-chambly",
        "montreal-stm" to "exo-lassomption",
        "montreal-stm" to "exo-laurentides",
        "montreal-stm" to "exo-presquile",
        "montreal-stm" to "exo-roussillon",
        "montreal-stm" to "exo-sainte-julie",
        "montreal-stm" to "exo-sorel-varennes",
        "montreal-stm" to "exo-sud-ouest",
        "montreal-stm" to "exo-terrebonne",
        "montreal-stm" to "exo-vallee-richelieu",
        "longueuil-rtl" to "exo-trains",
        "longueuil-rtl" to "exo-chambly",
        "longueuil-rtl" to "exo-lassomption",
        "longueuil-rtl" to "exo-roussillon",
        "longueuil-rtl" to "exo-sainte-julie",
        "longueuil-rtl" to "exo-sorel-varennes",
        "longueuil-rtl" to "exo-sud-ouest",
        "longueuil-rtl" to "exo-terrebonne",
        "longueuil-rtl" to "exo-vallee-richelieu",
        "exo-trains" to "exo-chambly",
        "exo-trains" to "exo-lassomption",
        "exo-trains" to "exo-laurentides",
        "exo-trains" to "exo-presquile",
        "exo-trains" to "exo-roussillon",
        "exo-trains" to "exo-sud-ouest",
        "exo-trains" to "exo-terrebonne",
        "exo-trains" to "exo-vallee-richelieu",
        "exo-chambly" to "exo-roussillon",
        "exo-chambly" to "exo-sainte-julie",
        "exo-chambly" to "exo-sorel-varennes",
        "exo-chambly" to "exo-vallee-richelieu",
        "exo-lassomption" to "exo-sorel-varennes",
        "exo-lassomption" to "exo-terrebonne",
        "exo-lassomption" to "exo-vallee-richelieu",
        "exo-laurentides" to "exo-terrebonne",
        "exo-roussillon" to "exo-sainte-julie",
        "exo-roussillon" to "exo-sorel-varennes",
        "exo-roussillon" to "exo-sud-ouest",
        "exo-roussillon" to "exo-vallee-richelieu",
        "exo-sainte-julie" to "exo-sorel-varennes",
        "exo-sainte-julie" to "exo-vallee-richelieu",
        "exo-sorel-varennes" to "exo-terrebonne",
        "exo-sorel-varennes" to "exo-vallee-richelieu",
        "exo-terrebonne" to "exo-vallee-richelieu",
    )

    /** Regions directly linked to [region]. */
    fun linked(region: Region): List<Region> = LINKS.mapNotNull { (a, b) ->
        when (region.id) { a -> byId(b); b -> byId(a); else -> null }
    }

    /** [region] first, then the regions reachable from it in at most [maxHops] links, nearest first. */
    fun reachable(region: Region, maxHops: Int = 3): List<Region> {
        val out = linkedSetOf(region)
        var frontier = listOf(region)
        repeat(maxHops) {
            frontier = frontier.flatMap { linked(it) }.filter { out.add(it) }
        }
        return out.toList()
    }

    /** Fewest-links path from [from] to [to], both included; null when they are not connected. */
    fun chain(from: Region, to: Region): List<Region>? {
        if (from == to) return listOf(from)
        val prev = HashMap<Region, Region>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for (n in linked(r)) {
                if (!seen.add(n)) continue
                prev[n] = r
                if (n == to) return generateSequence(to) { prev[it] }.toList().reversed()
                queue.addLast(n)
            }
        }
        return null
    }

    /** Smallest box holding all of [regions]: minLat, minLon, maxLat, maxLon. */
    fun boxOf(regions: List<Region>): DoubleArray = doubleArrayOf(
        regions.minOf { it.bbox[0] }, regions.minOf { it.bbox[1] },
        regions.maxOf { it.bbox[2] }, regions.maxOf { it.bbox[3] },
    )

    /**
     * The region serving this point, or null. Core areas are checked first (smallest wins),
     * then the full areas, so overlapping regions (Vancouver / Fraser Valley) resolve correctly.
     */
    fun find(lat: Double, lon: Double): Region? {
        for (secondary in listOf(false, true)) {
            ALL.filter { it.secondary == secondary && it.inCore(lat, lon) }.minByOrNull { it.coreArea }?.let { return it }
        }
        for (secondary in listOf(false, true)) {
            ALL.filter { it.secondary == secondary && it.contains(lat, lon) }.minByOrNull { it.bboxArea }?.let { return it }
        }
        return null
    }

    /** Display name of a province / state code. */
    fun areaName(code: String): String = when (code) {
        "BC" -> "British Columbia"
        "AB" -> "Alberta"
        "ON" -> "Ontario"
        "QC" -> "Québec"
        "MB" -> "Manitoba"
        "NS" -> "Nova Scotia"
        "SK" -> "Saskatchewan"
        "CA" -> "California (USA)"
        "TX" -> "Texas (USA)"
        "PA" -> "Pennsylvania (USA)"
        "GA" -> "Georgia (USA)"
        "FL" -> "Florida (USA)"
        "MA" -> "Massachusetts (USA)"
        "WA" -> "Washington (USA)"
        "IL" -> "Illinois (USA)"
        "CO" -> "Colorado (USA)"
        else -> code
    }
}
