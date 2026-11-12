# Buszi region data format (shared by Android, iOS and the pack builder)

A region's timetable is one **SQLite** file. The app either downloads a prebuilt pack
(`<region>.db.gz` from the `packs` GitHub Release) or builds the same file on the device
from the agency's GTFS zip. Layout: `core/src/commonMain/.../GtfsSchema.kt` (`VERSION = 4`,
also stored as SQLite `PRAGMA user_version` and in `meta.schema`).

## Tables
| Table | Content |
|---|---|
| `agency` | agency_id, name, url, tz |
| `routes` | route_id, agency_id, short_name, long_name, type (GTFS route_type), color, text_color (hex, no `#`) |
| `stops` | stop_id, code, name, lat, lon, wheelchair. Only boarding locations. `rowid` is the **stop ref** used in pattern blobs. |
| `patterns` | pattern (int), route_id, direction_id, headsign, `stops` BLOB (see below) |
| `trips` | (pattern, start_sec, trip_id, service) — `WITHOUT ROWID`, clustered by pattern + start; index on trip_id |
| `service_dates` | (date `yyyymmdd`, service int): the service runs on that date |
| `meta` | key/value: `city` (region id), `schema`, `imported_at` (epoch ms), `feed_version`, `feed_end_date`, `last_modified`, `pack_version` |
| `shapes` | pattern (int), `points` BLOB: the road of the pattern from GTFS shapes.txt, simplified to ~3 m, as zig-zag varint deltas of lat/lon in 1e-5 degrees (`ShapeCodec`). Added in version 4; empty if the feed has no shapes |

## Pattern blob
Sequence of stops, each written as two unsigned **LEB128 varints**:
1. `stopRef` = `stops.rowid`
2. `(delta << 1) | canBoard` where `delta` = seconds since the previous stop (first stop: since trip start),
   and `canBoard` = 0 at the last stop or where GTFS `pickup_type = 1`.

Departure time of a trip at stop *i* = `serviceDayStart(date) + trips.start_sec + Σ deltas[0..i]`.
`serviceDayStart` = local **noon − 12 h** of the service date in the region's time zone
(GTFS rule; correct on DST days). A trip runs on `date` if `(date, trips.service)` is in `service_dates`.
Times may exceed 24:00, so also check yesterday's service day.

Reference implementation: `PatternCodec` / `ServiceTime` in `core/src/commonMain/.../Schedule.kt`
(Kotlin Multiplatform — usable from iOS directly, or port ~40 lines to Swift).

## packs.json (Release asset)
```json
{ "schema": 3, "generated": "ISO-8601",
  "packs": { "<region id>": { "version": "...", "file": "<id>.db.gz", "bytes": 0, "dbBytes": 0, "sha256": "hex" } } }
```
Clients: skip if `schema` differs; verify SHA-256 of the `.gz`; gunzip; check `user_version`, `meta.city`,
and that `stops` is not empty; swap the file in only while it is closed.

## Clock calibration
If live (GTFS-RT) times and the timetable differ by a whole number of hours, the phone's time-zone
database is out of date (e.g. a province changed daylight-saving rules). Clients shift the region's
UTC offset by that many hours (see `TransitRepository.calibrateClock`).
