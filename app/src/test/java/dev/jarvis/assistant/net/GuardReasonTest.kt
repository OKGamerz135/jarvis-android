package dev.jarvis.assistant.net

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier N. Pure JVM, no OkHttp and no android.net. */
class GuardReasonTest {

    @Test
    fun n1a_guardViolation_isAnIOException() {
        val v: Any = GuardViolation(GuardReason.PEER_MISMATCH)
        assertTrue(v is IOException)
        assertTrue(v is GuardViolation)
    }

    @Test
    fun n1b_guardViolationException_isARuntimeException_andNotAnIOException() {
        val e: Any = GuardViolationException(GuardReason.PEER_MISMATCH)
        assertTrue(e is RuntimeException)
        assertFalse("must not be an IOException", e is IOException)
    }

    @Test
    fun n2_messageIsTheReasonNameOnly() {
        val all = GuardReason.values()
        assertEquals("data set must not be empty", 10, all.size)
        val shape = Regex("[A-Z0-9_]+")
        for (r in all) {
            val v = GuardViolation(r)
            val e = GuardViolationException(r)
            assertEquals(r.name, v.message)
            assertEquals(r.name, e.message)
            assertTrue("'${v.message}' must match [A-Z0-9_]+", shape.matches(v.message!!))
            assertTrue(shape.matches(e.message!!))
            assertEquals(null, v.cause)
            assertEquals(null, e.cause)
        }
    }

    @Test
    fun n3_bothExceptionTypesCarryTheReason() {
        val all = GuardReason.values()
        assertEquals("data set must not be empty", 10, all.size)
        for (r in all) {
            assertSame(r, GuardViolation(r).reason)
            assertSame(r, GuardViolationException(r).reason)
        }
    }

    @Test
    fun n4_enumHasExactlyTheTenDesignedValues() {
        val expected = setOf(
            "DNS_PROHIBITED_ADDRESS",
            "DNS_NO_IPV4",
            "BIND_FAILED",
            "CONNECT_PROHIBITED",
            "PEER_MISMATCH",
            "DNS_SECOND_LOOKUP",
            "PROTOCOL_NOT_HTTP11",
            "SECOND_NETWORK_PASS",
            "NETWORK_CHANGED",
            "NETWORK_STATE_UNAVAILABLE",
        )
        assertEquals(10, expected.size)
        assertEquals(expected, GuardReason.values().map { it.name }.toSet())
        assertEquals("no duplicate names", 10, GuardReason.values().size)
    }
}
