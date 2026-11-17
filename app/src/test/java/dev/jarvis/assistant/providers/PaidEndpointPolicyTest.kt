package dev.jarvis.assistant.providers

import dev.jarvis.assistant.providers.PaidEndpointPolicy.Consent
import dev.jarvis.assistant.providers.PaidEndpointPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D6: a custom endpoint may cost money, so nothing is sent until the user accepts a warning for that exact endpoint. */
class PaidEndpointPolicyTest {
    @Test fun noConsentMeansNeedsConsentWithAChargeWarning() {
        val d = PaidEndpointPolicy.evaluate("https://api.example.com/v1", null) as Decision.NeedsConsent
        assertEquals("https://api.example.com", d.endpoint)
        assertTrue(d.warning.contains("cost you money"))
        assertTrue(d.warning.contains("https://api.example.com"))
    }

    @Test fun theWarningNeverCallsTheEndpointFree() {
        val w = PaidEndpointPolicy.warning("https://api.example.com").lowercase()
        assertTrue(w.contains("cannot tell whether it is free or paid"))
        assertFalse(w.contains("is free") && !w.contains("whether it is free"))
        assertFalse(w.contains("no cost") || w.contains("at no charge") || w.contains("free to use"))
    }

    @Test fun consentForTheSameEndpointAllowsItEvenWithDifferentCaseOrDefaultPort() {
        val consent = Consent(PaidEndpointPolicy.normalize("https://API.Example.com:443/x")!!)
        assertTrue(PaidEndpointPolicy.evaluate("https://api.example.com/other", consent) is Decision.Allowed)
    }

    @Test fun consentIsBoundToTheEndpointSoEditingTheAddressAsksAgain() {
        val consent = Consent("https://api.example.com")
        assertTrue(PaidEndpointPolicy.evaluate("https://evil.example.net", consent) is Decision.NeedsConsent)
        assertTrue(PaidEndpointPolicy.evaluate("https://api.example.com:8443", consent) is Decision.NeedsConsent)
        assertTrue(PaidEndpointPolicy.evaluate("https://api.example.com.evil.net", consent) is Decision.NeedsConsent)
    }

    @Test fun onlyPlainHttpsWithAHostIsAccepted() {
        for (bad in listOf("http://api.example.com", "ftp://x.com", "https://user:pw@api.example.com", "api.example.com", "https://", "", "not a url", "https://a b.com"))
            assertTrue("for '$bad'", PaidEndpointPolicy.evaluate(bad, Consent("https://api.example.com")) is Decision.Rejected)
        assertNull(PaidEndpointPolicy.normalize("http://api.example.com"))
    }

    @Test fun consentForSomethingElseNeverAllowsARejectedAddress() {
        assertTrue(PaidEndpointPolicy.evaluate("http://api.example.com", Consent("http://api.example.com")) is Decision.Rejected)
    }
}
