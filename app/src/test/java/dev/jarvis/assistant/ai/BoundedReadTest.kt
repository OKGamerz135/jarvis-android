package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.net.SocketTimeoutException

class BoundedReadTest {
    private fun reader(s: String) = BufferedReader(StringReader(s))

    @Test fun readBoundedReturnsSmallBodiesUnchanged() {
        assertEquals("héllo 🙂", StringReader("héllo 🙂").readBounded(100))
        assertEquals("", StringReader("").readBounded(10))
    }

    @Test fun readBoundedStopsAtTheLimit() {
        assertEquals("a".repeat(100), StringReader("a".repeat(100)).readBounded(100))
        assertThrows(ResponseTooLargeException::class.java) { StringReader("a".repeat(101)).readBounded(100) }
    }

    @Test fun boundedLinesMatchReadLineForAllLineEndings() {
        val text = "one\ntwo\r\nthree\rfour\n\nlast-without-newline"
        val a = reader(text); val b = reader(text)
        val expected = generateSequence { a.readLine() }.toList()
        val actual = generateSequence { b.readBoundedLine() }.toList()
        assertEquals(expected, actual)
        assertEquals(listOf("one", "two", "three", "four", "", "last-without-newline"), actual)
    }

    @Test fun boundedLineEndsAtEofWithNull() {
        val r = reader("x")
        assertEquals("x", r.readBoundedLine())
        assertNull(r.readBoundedLine())
        assertNull(reader("").readBoundedLine())
    }

    @Test fun anOverlongLineIsRefusedButNormalLinesAfterShortOnesAreFine() {
        assertEquals("a".repeat(50), reader("a".repeat(50) + "\n").readBoundedLine(50))
        assertThrows(ResponseTooLargeException::class.java) { reader("a".repeat(51) + "\n").readBoundedLine(50) }
        assertThrows(ResponseTooLargeException::class.java) { reader("a".repeat(5000)).readBoundedLine(50) }
    }

    @Test fun oversizeIsAFailedCallNotAnOfflinePhone() {
        val e = ioFailure(ResponseTooLargeException("big"))
        assertTrue(e.toString(), e is LlmError.Other)
        assertTrue(ioFailure(java.io.IOException("reset")) == LlmError.Offline)
        assertTrue(ioFailure(SocketTimeoutException("Read timed out")) is LlmError.Timeout)
    }
}
