package dev.jarvis.assistant.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiagnosticsSupportTest {
    private fun entry(t: Long, kind: CallKind, provider: String = "P", ok: Boolean = false) = DiagnosticEntry(
        t, kind, provider, "m", "POST", "host/path", if (ok) 200 else 503, if (ok) null else "UNAVAILABLE",
        "says \"hi\"\nline2", 120, 1, ok, 42,
    )

    @Test
    fun codecRoundTrips() {
        val list = listOf(entry(1, CallKind.AVAILABILITY, ok = true), entry(2, CallKind.GENERATE_STREAM))
        assertEquals(list, DiagnosticsCodec.decode(DiagnosticsCodec.encode(list)))
    }

    @Test
    fun decodeToleratesGarbage() {
        assertEquals(emptyList<DiagnosticEntry>(), DiagnosticsCodec.decode("not json"))
        assertEquals(emptyList<DiagnosticEntry>(), DiagnosticsCodec.decode(""))
    }

    @Test
    fun availabilityChecksDoNotCountAsGenerationUsage() {
        val list = listOf(
            entry(1000, CallKind.AVAILABILITY), entry(2000, CallKind.GENERATE_STREAM),
            entry(3000, CallKind.GENERATE_ONCE), entry(4000, CallKind.GENERATE_STREAM, provider = "Other"),
        )
        assertEquals(2, UsageGuard.generationCalls(list, "P", 0))
        assertEquals(1, UsageGuard.generationCalls(list, "P", 2500))
    }

    @Test
    fun cooldownOnlyFollowsGenerationCalls() {
        val avail = listOf(entry(1000, CallKind.AVAILABILITY))
        assertEquals(0L, UsageGuard.cooldownRemainingMs(avail, "P", 1500))
        val gen = listOf(entry(1000, CallKind.GENERATE_STREAM))
        assertEquals(50_000L, UsageGuard.cooldownRemainingMs(gen, "P", 11_000))
        assertEquals(0L, UsageGuard.cooldownRemainingMs(gen, "P", 70_000))
        assertEquals(0L, UsageGuard.cooldownRemainingMs(gen, "Other", 1500))
    }

    @Test
    fun textReportHasModelEndpointAndNoSecrets() {
        val text = DiagnosticsFormat.toText(
            listOf(entry(1_700_000_000_000, CallKind.GENERATE_STREAM)), "build 4", Instant.EPOCH, ZoneId.of("UTC"),
        )
        assertTrue(text, text.contains("model m"))
        assertTrue(text, text.contains("POST host/path"))
        assertTrue(text, text.contains("HTTP 503"))
        assertTrue(text, text.contains("UNAVAILABLE"))
        assertFalse(text, text.contains("AIza"))
    }

    @Test
    fun usageAndCooldownAreNeverSharedBetweenDifferentLabels() {
        // Labels are matched exactly. The registry guarantees no two providers share a normalized label, so providers
        // that differ only slightly (a prefix, another case) keep completely separate usage and cooldown.
        val list = listOf(
            entry(1000, CallKind.GENERATE_STREAM, provider = "Groq"),
            entry(2000, CallKind.GENERATE_ONCE, provider = "Groq proxy"),
        )
        assertEquals(1, UsageGuard.generationCalls(list, "Groq", 0))
        assertEquals(1, UsageGuard.generationCalls(list, "Groq proxy", 0))
        assertEquals(0, UsageGuard.generationCalls(list, "groq", 0))
        assertEquals(59_000L, UsageGuard.cooldownRemainingMs(list, "Groq", 2000))
        assertEquals(0L, UsageGuard.cooldownRemainingMs(listOf(entry(1000, CallKind.GENERATE_STREAM, provider = "Groq")), "Groq proxy", 1500))
    }
}
