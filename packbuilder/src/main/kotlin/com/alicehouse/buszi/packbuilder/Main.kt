package com.alicehouse.buszi.packbuilder

import com.alicehouse.buszi.data.GtfsSchema
import com.alicehouse.buszi.data.GtfsWriter
import com.alicehouse.buszi.data.Region
import com.alicehouse.buszi.data.Regions
import com.alicehouse.buszi.data.SqlSink
import com.alicehouse.buszi.data.SqlStatement
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Builds one compressed SQLite pack per region (same layout the app uses), plus packs.json.
 *
 * Only regions whose licence allows hosting a transformed copy ([Region.packAllowed]) are built,
 * unless --local-test is given (for size/speed testing on your own machine; never publish those).
 *
 * Usage: --out <dir> [--regions id1,id2] [--local-test]
 */
fun main(args: Array<String>) {
    val opts = args.toList()
    fun value(name: String) = opts.indexOf(name).takeIf { it >= 0 }?.let { opts.getOrNull(it + 1) }
    val out = File(value("--out") ?: "build/packs").apply { mkdirs() }
    val localTest = "--local-test" in opts
    val wanted = value("--regions")?.split(',')?.map { it.trim() }?.toSet()

    val regions = Regions.ALL
        .filter { wanted == null || it.id in wanted }
        .filter { localTest || it.packAllowed }
    if (regions.isEmpty()) {
        System.err.println("No regions to build (packAllowed regions: ${Regions.ALL.filter { it.packAllowed }.map { it.id }})")
        exitProcess(1)
    }

    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
    val entries = ArrayList<String>()
    var failures = 0
    for (r in regions) {
        val t0 = System.currentTimeMillis()
        try {
            val e = buildPack(r, out, http)
            entries.add(e)
            println("OK   ${r.id} in ${(System.currentTimeMillis() - t0) / 1000}s")
        } catch (e: Exception) {
            failures++
            System.err.println("FAIL ${r.id}: ${e.message}")
        }
    }
    File(out, "packs.json").writeText(
        "{\n  \"schema\": ${GtfsSchema.VERSION},\n  \"generated\": \"${Instant.now()}\",\n  \"packs\": {\n" +
            entries.joinToString(",\n") + "\n  }\n}\n"
    )
    println("Wrote ${entries.size} packs to ${out.absolutePath} ($failures failed)")
    if (entries.isEmpty()) exitProcess(1)
}

private fun buildPack(r: Region, out: File, http: HttpClient): String {
    val zip = File.createTempFile("gtfs-${r.id}", ".zip")
    val db = File(out, "${r.id}.db")
    val gz = File(out, "${r.id}.db.gz")
    try {
        val resp = http.send(
            HttpRequest.newBuilder(URI(r.staticGtfsUrl)).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Buszi-packbuilder/1.0").build(),
            HttpResponse.BodyHandlers.ofFile(zip.toPath()),
        )
        check(resp.statusCode() == 200) { "HTTP ${resp.statusCode()}" }
        val lastModified = resp.headers().firstValue("Last-Modified").orElse("")

        db.delete()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { it.execute("PRAGMA journal_mode=OFF"); it.execute("PRAGMA synchronous=OFF") }
            c.autoCommit = false
            val sink = JdbcSink(c)
            GtfsSchema.CREATE.forEach(sink::exec)
            val packVersion = Instant.now().epochSecond.toString()
            ZipFile(zip).use { z ->
                GtfsWriter(sink) { println("     ${r.id}: $it") }.writeAll(
                    z,
                    mapOf("city" to r.id, "pack_version" to packVersion, "last_modified" to lastModified),
                    r.excludeRouteTypes,
                )
            }
            sink.closeAll()
            c.commit()
            c.autoCommit = true
            // Android's SQLiteOpenHelper checks this; it must match GtfsDatabase.VERSION.
            c.createStatement().use { it.execute("PRAGMA user_version=${GtfsSchema.VERSION}"); it.execute("VACUUM") }
        }
        GZIPOutputStream(gz.outputStream().buffered(), 1 shl 16).use { o -> db.inputStream().use { it.copyTo(o) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(gz.readBytes()).joinToString("") { "%02x".format(it) }
        val version = DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT v FROM meta WHERE k='pack_version'").use { it.next(); it.getString(1) } }
        }
        println("     ${r.id}: db=${db.length() / 1024} KB, gz=${gz.length() / 1024} KB")
        return "    \"${r.id}\": {\"version\": \"$version\", \"file\": \"${gz.name}\", \"bytes\": ${gz.length()}, " +
            "\"dbBytes\": ${db.length()}, \"sha256\": \"$sha\"}"
    } finally {
        zip.delete()
        db.delete()
    }
}

private class JdbcSink(private val c: Connection) : SqlSink {
    private val open = ArrayList<PreparedStatement>()
    fun closeAll() { open.forEach { runCatching { it.close() } }; open.clear() }
    override fun exec(sql: String) { c.createStatement().use { it.execute(sql) } }
    override fun prepare(sql: String): SqlStatement = JdbcStatement(c.prepareStatement(sql).also { open.add(it) })
    override fun pairs(sql: String): List<Pair<String, Int>> =
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) } }
        }
}

private class JdbcStatement(private val p: PreparedStatement) : SqlStatement {
    override fun bind(index: Int, value: String) = p.setString(index, value)
    override fun bind(index: Int, value: Long) = p.setLong(index, value)
    override fun bind(index: Int, value: Double) = p.setDouble(index, value)
    override fun bind(index: Int, value: ByteArray) = p.setBytes(index, value)
    override fun insert() { p.executeUpdate(); p.clearParameters() }
}
