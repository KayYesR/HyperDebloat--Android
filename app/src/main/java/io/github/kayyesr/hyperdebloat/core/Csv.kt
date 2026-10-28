package io.github.kayyesr.hyperdebloat.core

/** CSV in the same shape as PowerShell's Export-Csv -NoTypeInformation: every field quoted. */
object Csv {

    fun field(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Boolean -> if (v) "True" else "False"
            else -> v.toString()
        }
        return "\"" + s.replace("\"", "\"\"") + "\""
    }

    fun write(header: List<String>, rows: List<List<Any?>>): String {
        val sb = StringBuilder()
        sb.append(header.joinToString(",") { field(it) }).append("\r\n")
        for (r in rows) sb.append(r.joinToString(",") { field(it) }).append("\r\n")
        return sb.toString()
    }

    /** Reads a CSV with a header row into maps (Import-Csv). Quoted fields may contain commas, quotes and newlines. */
    fun read(text: String): List<Map<String, String>> {
        val records = parse(text.removePrefix("\uFEFF"))
        if (records.isEmpty()) return emptyList()
        val header = records[0]
        return records.drop(1).filter { it.size > 1 || (it.size == 1 && it[0].isNotEmpty()) }.map { rec ->
            header.indices.associate { i -> header[i] to (rec.getOrNull(i) ?: "") }
        }
    }

    private fun parse(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var rec = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        var inQ = false
        while (i < text.length) {
            val c = text[i]
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else inQ = false
                } else cur.append(c)
            } else {
                when (c) {
                    '"' -> inQ = true
                    ',' -> { rec.add(cur.toString()); cur.setLength(0) }
                    '\r' -> {}
                    '\n' -> { rec.add(cur.toString()); cur.setLength(0); out.add(rec); rec = ArrayList() }
                    else -> cur.append(c)
                }
            }
            i++
        }
        if (cur.isNotEmpty() || rec.isNotEmpty()) { rec.add(cur.toString()); out.add(rec) }
        return out
    }
}
