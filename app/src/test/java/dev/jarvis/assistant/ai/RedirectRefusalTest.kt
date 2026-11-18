package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectRefusalTest {

    @Test fun everyThreeHundredStatusCountsAndNothingElseDoes() {
        for (s in 300..399) assertTrue("$s", RedirectRefusal.isRedirect(s))
        for (s in listOf(100, 101, 200, 201, 204, 206, 299, 400, 401, 404, 429, 500, 503)) assertFalse("$s", RedirectRefusal.isRedirect(s))
    }

    @Test fun onlyTheHostIsEverShown() {
        assertEquals("example.com", RedirectRefusal.targetHost("https://example.com/path?q=1#frag"))
        assertEquals("example.com", RedirectRefusal.targetHost("https://user:pass@example.com:8443/x"))
        assertEquals("example.com", RedirectRefusal.targetHost("//example.com/x"))
        assertEquals("example.com", RedirectRefusal.targetHost("HTTPS://EXAMPLE.com/"))
    }

    @Test fun noHostIsShownWhenThereIsNoneOrItIsNotAPlainHostName() {
        assertNull(RedirectRefusal.targetHost(null))
        assertNull(RedirectRefusal.targetHost(""))
        assertNull(RedirectRefusal.targetHost("   "))
        assertNull(RedirectRefusal.targetHost("/relative/path"))
        assertNull(RedirectRefusal.targetHost("not a url at all"))
        assertNull(RedirectRefusal.targetHost("http://exa mple.com/"))
        assertNull(RedirectRefusal.targetHost("javascript:alert(1)"))
        assertNull(RedirectRefusal.targetHost("http://<script>.example/"))
        assertNull(RedirectRefusal.targetHost("http://" + "a".repeat(200) + ".example/"))
    }

    @Test fun aHostThatCarriesTheKeyIsNotShown() {
        val key = "SUPERSECRETKEYVALUE1234567890"
        assertNull(RedirectRefusal.targetHost("http://$key.example.com/", listOf(key)))
        assertEquals("example.com", RedirectRefusal.targetHost("http://example.com/?k=$key", listOf(key)))
    }

    @Test fun theMessageMentionsTheRedirectTheStatusAndTheHostButNeverThePathQueryUserinfoOrKey() {
        val key = "SUPERSECRETKEYVALUE1234567890"
        val m = RedirectRefusal.message(302, "http://user:pass@evil.example:8443/steal/here?token=$key", listOf(key))
        assertTrue(m, m.contains("redirect", ignoreCase = true))
        assertTrue(m, m.contains("302"))
        assertTrue(m, m.contains("evil.example"))
        for (bad in listOf("user", "pass", "8443", "/steal", "token", key)) {
            assertFalse("\"$bad\" must not appear: $m", m.contains(bad))
        }
    }

    @Test fun theMessageStillWorksWithoutAnyUsableLocation() {
        for (loc in listOf(null, "", "garbage with spaces")) {
            val m = RedirectRefusal.message(307, loc)
            assertTrue(m, m.contains("redirect", ignoreCase = true))
            assertTrue(m, m.contains("307"))
            assertFalse(m, m.contains("pointing"))
        }
        assertTrue(RedirectRefusal.message(301, "/elsewhere").contains("same server"))
    }

    @Test fun theErrorIsAPlainOtherErrorSoItIsNeverRetriedNorTreatedAsQuota() {
        val e = RedirectRefusal.error(302, "https://example.com/")
        assertFalse(RetryPolicy.shouldRetry(e))
        assertEquals(e.message, ErrorMessages.of(e))
    }
}
