package dev.jarvis.assistant.net

import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier N. t04a, t04b and t04d are ported from the S1 prototype suite with their assertions
 * unchanged (only the class under test moved). N12 and N13 are new.
 */
class HostRuleTest {

    private val badHosts = listOf(
        "127.0.0.1", "127.1", "2130706433", "017700000001", "0x7f.0.0.1", "0x7f.1", "127.0.0.0xff",
        "1.2.3.4", "1.2.3", "[::1]", "::1", "::ffff:127.0.0.1", "example.com.", "localhost", "a..b",
        "-a.com", "a-.com", "exa_mple.com", "ex%61mple.com", "例え.jp", "exa mple.com", "ab--cd.com",
        "xn--.net", "a.b.123", "host.0x1f", "",
    )

    @Test
    fun t04a_hostrule_rejects_literals_and_alt_numeric_forms() {
        assertEquals("data set size", 26, badHosts.size)
        for (h in badHosts) assertNull("'$h' must be rejected", HostRule.validate(h))
    }

    @Test
    fun t04b_hostrule_keeps_legitimate_names_and_agrees_with_okhttp_canonical_form() {
        val good = listOf(
            "api.example.com", "123.example.com", "api.v2.example.com", "my-host.example.org", "a.b",
            "xn--n3h.net", "1e100.net", "10-0-0-5.example.test", "API.Example.COM",
        )
        assertEquals("data set size", 9, good.size)
        for (h in good) {
            val c = HostRule.validate(h)
            assertNotNull("'$h' must be accepted", c)
            assertEquals(c, HttpUrl.Builder().scheme("https").host(c!!).build().host)
        }
        assertEquals("api.example.com", HostRule.validate("API.Example.COM"))
    }

    @Test
    fun t04d_hostrule_rejects_chars_that_lowercase_to_ascii() {
        assertEquals("sanity: the JDK maps KELVIN SIGN to ascii k", "k", "K".lowercase())
        val bad = listOf("Key.example.com", "api.Kest.example.com", "api.example.cKm")
        assertEquals("data set size", 3, bad.size)
        for (h in bad) assertNull("'$h' must be rejected", HostRule.validate(h))
    }

    @Test
    fun n12_output_is_lowercase_and_validation_is_idempotent() {
        val inputs = listOf("API.Example.COM", "Api.Example.Com", "EXAMPLE.ORG", "a.B", "XN--N3H.NET", "My-Host.Example.Test")
        assertEquals("data set size", 6, inputs.size)
        for (raw in inputs) {
            val c = HostRule.validate(raw)
            assertNotNull("'$raw' must be accepted", c)
            assertEquals(raw.lowercase(), c)
            assertTrue("'$c' must have no uppercase letter", c!!.none { it in 'A'..'Z' })
            assertEquals("validating the canonical form must not change it", c, HostRule.validate(c))
        }
    }

    @Test
    fun n13_length_limits_at_63_64_and_253_254() {
        val l63 = "a".repeat(63)
        val l64 = "a".repeat(64)
        assertEquals(63, l63.length)
        assertEquals(64, l64.length)
        assertEquals("63-char label is accepted", "$l63.com", HostRule.validate("$l63.com"))
        assertNull("64-char label is rejected", HostRule.validate("$l64.com"))

        val host253 = "$l63.$l63.$l63." + "b".repeat(61)
        val host254 = "$l63.$l63.$l63." + "b".repeat(62)
        assertEquals(253, host253.length)
        assertEquals(254, host254.length)
        assertEquals("253-char host is accepted", host253, HostRule.validate(host253))
        assertNull("254-char host is rejected", HostRule.validate(host254))
    }
}
