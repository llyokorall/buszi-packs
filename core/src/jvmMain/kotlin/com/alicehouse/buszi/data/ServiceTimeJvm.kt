package com.alicehouse.buszi.data

/*
 * java.time bridges for Android and the pack builder. Common code (and iOS) uses kotlinx-datetime;
 * these overloads let JVM callers keep using java.time.LocalDate / ZoneId.
 */

/** GTFS reference point: "noon minus 12 h" local time (correct on DST change days). */
fun ServiceTime.dayStart(date: java.time.LocalDate, zone: java.time.ZoneId): Long =
    date.atTime(12, 0).atZone(zone).toEpochSecond() - 12 * 3600

fun ServiceTime.dateInt(d: java.time.LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
