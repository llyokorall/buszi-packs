package com.alicehouse.buszi.data

import java.io.Reader

/**
 * Small streaming CSV reader (RFC 4180: quoted fields, "" escapes, quoted newlines).
 * Written from scratch so we carry no extra third-party licence.
 */
class CsvReader(private val reader: Reader) {
    private val buf = CharArray(64 * 1024)
    private var len = 0
    private var pos = 0
    private var eof = false

    private fun next(): Int {
        if (pos >= len) {
            if (eof) return -1
            len = reader.read(buf)
            pos = 0
            if (len <= 0) { eof = true; return -1 }
        }
        return buf[pos++].code
    }

    private fun peek(): Int {
        if (pos >= len) {
            if (eof) return -1
            len = reader.read(buf)
            pos = 0
            if (len <= 0) { eof = true; return -1 }
        }
        return buf[pos].code
    }

    /** Returns the next record, or null at end of input. */
    fun readRecord(): List<String>? {
        var c = next()
        if (c == -1) return null
        val fields = ArrayList<String>(16)
        val sb = StringBuilder()
        var inQuotes = false
        while (true) {
            if (inQuotes) {
                when {
                    c == -1 -> { fields.add(sb.toString()); return fields }
                    c == '"'.code -> if (peek() == '"'.code) { next(); sb.append('"') } else inQuotes = false
                    else -> sb.append(c.toChar())
                }
            } else {
                when (c) {
                    -1 -> { fields.add(sb.toString()); return fields }
                    '"'.code -> inQuotes = true
                    ','.code -> { fields.add(sb.toString()); sb.setLength(0) }
                    '\r'.code -> { /* ignore */ }
                    '\n'.code -> { fields.add(sb.toString()); return fields }
                    else -> sb.append(c.toChar())
                }
            }
            c = next()
        }
    }
}

/** CSV with a header row; gives column lookup by name. */
class CsvTable(reader: Reader) {
    private val csv = CsvReader(reader)
    private val index: Map<String, Int>

    init {
        val header = csv.readRecord() ?: emptyList()
        index = header.withIndex().associate { (i, name) ->
            name.trim().removePrefix("\uFEFF") to i
        }
    }

    fun col(name: String): Int = index[name] ?: -1

    inline fun forEachRow(block: (List<String>) -> Unit) {
        while (true) {
            val row = nextRow() ?: break
            if (row.size == 1 && row[0].isEmpty()) continue
            block(row)
        }
    }

    fun nextRow(): List<String>? = csv.readRecord()
}

fun List<String>.at(i: Int): String = if (i in indices) this[i].trim() else ""
