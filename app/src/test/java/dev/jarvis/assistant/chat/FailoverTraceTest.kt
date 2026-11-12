package dev.jarvis.assistant.chat

import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTraceTest {
    @Test fun hostIsExtractedWithoutPathOrQuery() {
        assertEquals("api.groq.com", hostOf("api.groq.com/openai/v1/chat/completions"))
        assertEquals("api.groq.com", hostOf("https://api.groq.com/openai/v1/chat/completions?x=1"))
        assertEquals("generativelanguage.googleapis.com", hostOf("generativelanguage.googleapis.com/v1beta/models/m:streamGenerateContent"))
    }

    @Test fun failoverEntriesNeverCountAsQuotaUse() {
        assertFalse(CallKind.FAILOVER.usesGenerationQuota)
        assertTrue(CallKind.GENERATE_STREAM.usesGenerationQuota)
        assertTrue(CallKind.GENERATE_ONCE.usesGenerationQuota)
        assertFalse(CallKind.AVAILABILITY.usesGenerationQuota)
    }

    @Test fun sinkTakesHostAndStatusFromTheAdaptersOwnEntry() {
        val written = mutableListOf<DiagnosticEntry>()
        val real = DiagnosticEntry(
            1500, CallKind.GENERATE_STREAM, AiProvider.GROQ.label, "m", "POST", "api.groq.com/openai/v1/chat/completions",
            200, null, null, 80, 1, true, 100,
        )
        val older = real.copy(timeMillis = 10, httpStatus = 429, endpoint = "generativelanguage.googleapis.com/x", provider = AiProvider.GEMINI.label)
        val sink = DiagnosticsTraceSink(object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { written += entry } }, { listOf(older, real) }, { 2000 })
        sink.record(
            TraceEvent(
                AiProvider.GEMINI, "gm", "QuotaExhausted", AiProvider.GROQ, "m", "GroqProvider", "attempt finished",
                streamStarted = true, responseReceived = true, dispatchedAtMillis = 1000, result = "success",
            ),
        )
        val e = written.single()
        assertEquals(CallKind.FAILOVER, e.kind)
        assertEquals("api.groq.com", e.endpoint)
        assertEquals(200, e.httpStatus)
        assertTrue(e.ok)
        assertTrue(e.message!!.contains("adapter=GroqProvider"))
        assertTrue(e.message!!.contains("streamStarted=true"))
        assertEquals("QuotaExhausted", e.apiStatus)
    }

    @Test fun dispatchMismatchIsFlaggedButSimulationIsNot() {
        val ok = DispatchInfo(AiProvider.GEMINI, AiProvider.GROQ, "GroqProvider", "m", DispatchStage.STREAMING, false)
        assertFalse(ok.mismatch)
        assertTrue(ok.copy(adapter = "GeminiProvider").mismatch)
        assertFalse(ok.copy(adapter = "SimulatedQuotaProvider", simulated = true).mismatch)
        assertEquals("Google Gemini → GroqProvider → m", ok.line)
    }
}
