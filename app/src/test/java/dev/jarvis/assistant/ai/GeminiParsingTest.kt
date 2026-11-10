package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class GeminiParsingTest {
    // 2026-11-10 10:00 UTC = 02:00 Pacific (PST, UTC-8) = 15:30 in India.
    private val now = Instant.parse("2026-11-10T10:00:00Z")

    private fun error429(vararg details: String): String =
        """{"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED","details":[${details.joinToString(",")}]}}"""

    private fun quotaFailure(quotaId: String) =
        """{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"m","quotaId":"$quotaId"}]}"""

    private fun retryInfo(delay: String) =
        """{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$delay"}"""

    @Test
    fun dailyQuotaIsExhaustedEvenWhenRetryDelayIsTiny() {
        val body = error429(quotaFailure("GenerateRequestsPerDayPerProjectPerModel-FreeTier"), retryInfo("1s"))
        val e = GeminiParsing.parseError(429, body, "m", now)
        assertTrue(e is LlmError.QuotaExhausted)
        e as LlmError.QuotaExhausted
        // Next midnight Pacific: 2026-11-11 00:00 PST = 08:00 UTC.
        assertEquals(Instant.parse("2026-11-11T08:00:00Z"), e.resetAt)
        assertEquals("GenerateRequestsPerDayPerProjectPerModel-FreeTier", e.quotaId)
    }

    @Test
    fun perMinuteLimitIsOrdinaryRateLimiting() {
        val body = error429(quotaFailure("GenerateRequestsPerMinutePerProjectPerModel-FreeTier"), retryInfo("34.5s"))
        val e = GeminiParsing.parseError(429, body, "m", now)
        assertEquals(LlmError.RateLimited(35L), e)
    }

    @Test
    fun dailyWinsWhenBothLimitsAreMentioned() {
        val body = error429(
            quotaFailure("GenerateRequestsPerMinutePerProjectPerModel-FreeTier"),
            quotaFailure("GenerateRequestsPerDayPerProjectPerModel-FreeTier"),
        )
        assertTrue(GeminiParsing.parseError(429, body, "m", now) is LlmError.QuotaExhausted)
    }

    @Test
    fun a429WithoutDetailsIsUnclearAndNotTreatedAsExhausted() {
        val e = GeminiParsing.parseError(429, """{"error":{"code":429,"message":"Resource exhausted"}}""", "m", now)
        assertEquals(LlmError.Unclear429(null), e)
        assertEquals(LlmError.Unclear429(null), GeminiParsing.parseError(429, "not json at all", "m", now))
    }

    @Test
    fun serverErrorsAreNotQuotaProblems() {
        assertEquals(LlmError.ServerTrouble(503), GeminiParsing.parseError(503, """{"error":{"code":503}}""", "m", now))
        assertEquals(LlmError.ServerTrouble(500), GeminiParsing.parseError(500, "", "m", now))
    }

    @Test
    fun badKeyAndMissingModelAreRecognised() {
        val badKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertEquals(LlmError.InvalidKey, GeminiParsing.parseError(400, badKey, "m", now))
        assertEquals(LlmError.InvalidKey, GeminiParsing.parseError(401, "", "m", now))
        assertEquals(LlmError.ModelNotFound("gemini-x"), GeminiParsing.parseError(404, "", "gemini-x", now))
    }

    @Test
    fun retryDelayParsing() {
        assertEquals(1L, GeminiParsing.parseRetrySeconds("1s"))
        assertEquals(35L, GeminiParsing.parseRetrySeconds("34.001s"))
        assertEquals(null, GeminiParsing.parseRetrySeconds("soon"))
        assertEquals(null, GeminiParsing.parseRetrySeconds(""))
    }

    @Test
    fun streamedTextSkipsThoughtPartsAndJoinsTheRest() {
        val chunk = """{"candidates":[{"content":{"role":"model","parts":[{"text":"hidden","thought":true},{"text":"Hello"},{"text":", sir."}]}}]}"""
        assertEquals("Hello, sir.", GeminiParsing.textOf(chunk))
        assertEquals("", GeminiParsing.textOf("""{"usageMetadata":{}}"""))
        assertEquals("", GeminiParsing.textOf("garbage"))
    }

    @Test
    fun blockedPromptsThrowATypedError() {
        try {
            GeminiParsing.textOf("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            fail("expected an LlmException")
        } catch (e: LlmException) {
            assertEquals(LlmError.Blocked("SAFETY"), e.error)
        }
    }

    @Test
    fun requestBodyMapsRolesAndCarriesTheSystemPrompt() {
        val body = GeminiParsing.buildBody(
            ChatRequest(
                model = "m",
                apiKey = "k",
                systemPrompt = "be nice",
                messages = listOf(ChatMessage(ChatRole.USER, "hi"), ChatMessage(ChatRole.ASSISTANT, "hello")),
            ),
        )
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(body.contains("be nice"))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"role\":\"model\""))
        assertTrue("the API key must never be in the body", !body.contains("\"k\""))
    }

    @Test
    fun resetTimesAreDescribedInTheUsersOwnTimeZone() {
        val india = ZoneId.of("Asia/Kolkata")
        val reset = Instant.parse("2026-11-11T08:00:00Z") // 13:30 in India, the next day
        val far = ResetFormat.describe(reset, now, india, Locale.ENGLISH)
        assertTrue(far, far.contains("1:30") && far.contains("tomorrow"))
        assertEquals("in about 20 minutes", ResetFormat.describe(now.plusSeconds(20 * 60), now, india, Locale.ENGLISH))
        assertEquals("in under a minute", ResetFormat.describe(now.plusSeconds(20), now, india, Locale.ENGLISH))
        assertEquals("at a time I can't tell", ResetFormat.describe(null, now, india, Locale.ENGLISH))
    }
}
