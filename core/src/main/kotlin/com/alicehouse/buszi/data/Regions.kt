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
    /** One line shown under lists (home screen). */
    val shortCredit: String,
    /** Text the provider requires to be shown prominently, verbatim (static data). */
    val requiredNotice: String? = null,
    /** Extra verbatim text required when the live (API) feeds are used. */
    val liveNotice: String? = null,
    /** True only when the licence clearly allows hosting a transformed copy (prebuilt pack). */
    val packAllowed: Boolean = false,
    /** GTFS route types not imported (licence or data-quality reasons). */
    val excludeRouteTypes: Set<Int> = emptySet(),
) {
    fun contains(lat: Double, lon: Double, marginDeg: Double = 0.05, box: DoubleArray = bbox): Boolean =
        lat >= box[0] - marginDeg && lat <= box[2] + marginDeg &&
            lon >= box[1] - marginDeg && lon <= box[3] + marginDeg

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
    )

    val ALL: List<Region> = listOf(METRO_VANCOUVER) + BC_TRANSIT + EDMONTON + CANADA_OTHER + USA

    fun byId(id: String?): Region? = ALL.firstOrNull { it.id == id }

    /**
     * The region serving this point, or null. Core areas are checked first (smallest wins),
     * then the full areas, so overlapping regions (Vancouver / Fraser Valley) resolve correctly.
     */
    fun find(lat: Double, lon: Double): Region? =
        ALL.filter { it.contains(lat, lon, 0.02, it.core) }.minByOrNull { it.coreArea }
            ?: ALL.filter { it.contains(lat, lon) }.minByOrNull { it.bboxArea }

    /** Display name of a province / state code. */
    fun areaName(code: String): String = when (code) {
        "BC" -> "British Columbia"
        "AB" -> "Alberta"
        "ON" -> "Ontario"
        "QC" -> "Québec"
        "MB" -> "Manitoba"
        "NS" -> "Nova Scotia"
        "MA" -> "Massachusetts (USA)"
        "WA" -> "Washington (USA)"
        "IL" -> "Illinois (USA)"
        "CO" -> "Colorado (USA)"
        else -> code
    }
}
