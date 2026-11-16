package dev.jarvis.assistant.ai

import java.io.BufferedReader
import java.io.IOException
import java.io.Reader

/** A provider sent more data than any real reply needs. Treated as a failed call, not as being offline. */
class ResponseTooLargeException(message: String) : IOException(message)

const val MAX_BODY_CHARS = 2_000_000
const val MAX_LINE_CHARS = 1_000_000

/** Like readText(), but stops with [ResponseTooLargeException] instead of filling memory. */
fun Reader.readBounded(max: Int = MAX_BODY_CHARS): String {
    val out = StringBuilder()
    val buffer = CharArray(8 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.append(buffer, 0, n)
        if (out.length > max) throw ResponseTooLargeException("The reply is larger than $max characters.")
    }
    return out.toString()
}

/** Like readLine() (same line-ending rules), but a single line may not exceed [max] characters. */
fun BufferedReader.readBoundedLine(max: Int = MAX_LINE_CHARS): String? {
    val line = StringBuilder()
    var any = false
    while (true) {
        val c = read()
        if (c < 0) return if (any) line.toString() else null
        any = true
        if (c == '\n'.code) return line.toString()
        if (c == '\r'.code) {
            mark(1)
            val next = read()
            if (next != '\n'.code && next >= 0) reset()
            return line.toString()
        }
        line.append(c.toChar())
        if (line.length > max) throw ResponseTooLargeException("A line of the reply is longer than $max characters.")
    }
}
