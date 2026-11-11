# Buszi region packs

Builds compact timetable packs for the Buszi app from public transit GTFS feeds and publishes them
to the GitHub Release **packs** (daily, via GitHub Actions).

Only regions whose data licence clearly allows redistributing a transformed copy are built
(`packAllowed = true` in `core/.../Regions.kt`). Each pack keeps the source agency's required
attribution; see the region definitions for the licence of each feed.

The data belongs to the respective transit agencies. This repository contains only the build tool.
