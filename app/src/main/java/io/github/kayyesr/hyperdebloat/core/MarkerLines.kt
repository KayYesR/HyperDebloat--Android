package io.github.kayyesr.hyperdebloat.core

/**
 * Splits raw command output into lines and spots the end line [marker] that every command prints last.
 * Bytes are kept until a line is complete, so a character or the marker split across two reads is still found.
 */
class MarkerLines(private val marker: String) {
    private val pending = java.io.ByteArrayOutputStream()

    /** Feeds [n] bytes of [buf]. Returns true once the end line was seen (nothing after it is handed on). */
    fun feed(buf: ByteArray, n: Int, onLine: (String) -> Unit): Boolean {
        var from = 0
        for (i in 0 until n) {
            if (buf[i] != '\n'.code.toByte()) continue
            pending.write(buf, from, i - from)
            from = i + 1
            val line = pending.toString("UTF-8").trimEnd('\r')
            pending.reset()
            // The end line can sit at the end of a last line that had no line break of its own.
            if (line.endsWith(marker)) {
                val head = line.removeSuffix(marker)
                if (head.isNotEmpty()) onLine(head)
                return true
            }
            onLine(line)
        }
        pending.write(buf, from, n - from)
        return false
    }
}
