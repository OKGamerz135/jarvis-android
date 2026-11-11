package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsVerdictTest {
    private val ok = Outcome.Ok("fine")
    private val ctx = FailureContext("Google Gemini", "POST", "m", "host/p", 503, "UNAVAILABLE", "overloaded")
    private val overloaded = Outcome.Fail(LlmError.Overloaded("x"), ctx)
    private val skipped = Outcome.Skipped("n/a")

    @Test
    fun allGood() {
        assertTrue(DiagnosticsVerdict.verdict(ok, ok, skipped).startsWith("Everything works"))
    }

    @Test
    fun bothGenerationsOverloadedBlamesGoogleNotUs() {
        val v = DiagnosticsVerdict.verdict(ok, overloaded, overloaded)
        assertTrue(v, v.contains("isn't your key, the model name or JARVIS"))
    }

    @Test
    fun streamFailsButOnceWorksPointsAtOurCode() {
        val v = DiagnosticsVerdict.verdict(ok, overloaded, ok)
        assertTrue(v, v.contains("JARVIS's streaming code"))
    }

    @Test
    fun checkFailuresAreDistinguished() {
        val offline = DiagnosticsVerdict.verdict(Outcome.Fail(LlmError.Offline, null), skipped, skipped)
        assertTrue(offline, offline.contains("network problem"))
        val notFound = DiagnosticsVerdict.verdict(Outcome.Fail(LlmError.ModelNotFound("m"), ctx), skipped, skipped)
        assertTrue(notFound, notFound.contains("model name"))
        val badKey = DiagnosticsVerdict.verdict(Outcome.Fail(LlmError.InvalidKey, ctx), skipped, skipped)
        assertTrue(badKey, badKey.contains("rejects this key"))
    }

    @Test
    fun linesShowStatusAndSkips() {
        val lines = DiagnosticsVerdict.lines(ok, overloaded, skipped)
        assertEquals(listOf(true, false, null), lines.map { it.ok })
        assertTrue(lines[1].detail.contains("HTTP 503, UNAVAILABLE"))
        assertTrue(lines[2].detail.startsWith("skipped"))
    }

    @Test
    fun providerNameMatchesTheSettingsLabel() {
        assertEquals(dev.jarvis.assistant.settings.AiProvider.GEMINI.label, GeminiProvider.NAME)
    }
}
