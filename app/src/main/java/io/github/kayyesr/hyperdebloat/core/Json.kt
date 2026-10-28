package io.github.kayyesr.hyperdebloat.core

/**
 * Small dependency-free JSON reader and writer, so the rating logic runs (and is tested) on a plain JVM.
 * Objects become LinkedHashMap<String, Any?>, arrays List<Any?>, numbers Double, plus String, Boolean, null.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text.removePrefix("\uFEFF"))
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != p.s.length) p.fail("unexpected text after the end")
        return v
    }

    /** Writes a flat string map as a JSON object (used for removed_state.json). */
    fun stringify(map: Map<String, String>): String {
        val sb = StringBuilder("{\n")
        val entries = map.entries.toList()
        entries.forEachIndexed { i, (k, v) ->
            sb.append("    ").append(quote(k)).append(": ").append(quote(v))
            if (i < entries.size - 1) sb.append(',')
            sb.append('\n')
        }
        return sb.append('}').toString()
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("Invalid JSON at $i: $msg")

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("expected a key")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("expected :")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                if (i >= s.length) fail("unexpected end in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("expected , or }")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (i >= s.length) fail("unexpected end in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("expected , or ]")
                }
            }
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (start == i) fail("unexpected character '${s[i]}'")
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }
    }
}

/** Typed helpers for walking parsed JSON. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asList(): List<Any?>? = this as? List<Any?>

internal fun Any?.asText(): String = when (this) {
    null -> ""
    is String -> this
    is Double -> if (this == Math.floor(this) && !this.isInfinite()) this.toLong().toString() else this.toString()
    else -> this.toString()
}
