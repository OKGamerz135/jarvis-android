package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GroqParsingTest {
    private val now = Instant.parse("2026-11-11T10:00:00Z")

    private fun err(code: String, message: String, type: String = "tokens") =
        """{"error":{"message":"$message","type":"$type","code":"$code"}}"""

    private fun parse(status: Int, body: String, retry: String? = null) =
        GroqParsing.parseFailure(status, body, retry, "openai/gpt-oss-120b", now)

    @Test
    fun gptOssGetsLowEffortAndHiddenReasoning() {
        val req = ChatRequest("openai/gpt-oss-120b", "k", "sys", listOf(ChatMessage(ChatRole.USER, "hi")))
        val body = GroqParsing.buildBody(req, stream = true)
        assertTrue(body, body.contains("\"reasoning_effort\":\"low\""))
        assertTrue(body, body.contains("\"include_reasoning\":false"))
        assertTrue(body, body.contains("\"stream\":true"))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test
    fun otherModelsGetNoReasoningFields() {
        val req = ChatRequest("some/other-model", "k", "sys", listOf(ChatMessage(ChatRole.ASSISTANT, "yo")))
        val body = GroqParsing.buildBody(req, stream = false)
        assertFalse(body, body.contains("reasoning"))
        assertTrue(body.contains("\"role\":\"assistant\""))
        assertFalse(body.contains("\"k\""))
    }

    @Test
    fun onlyDeltaContentIsReadNeverReasoning() {
        assertEquals("Hi", GroqParsing.deltaText("""{"choices":[{"delta":{"content":"Hi","reasoning":"secret thoughts"}}]}"""))
        assertEquals("", GroqParsing.deltaText("""{"choices":[{"delta":{"reasoning":"only thoughts"}}]}"""))
        assertEquals("", GroqParsing.deltaText("""{"choices":[{"delta":{"content":null}}]}"""))
        assertEquals("", GroqParsing.deltaText("garbage"))
        assertEquals("ok", GroqParsing.messageText("""{"choices":[{"message":{"content":"ok","reasoning":"x"}}]}"""))
    }

    @Test
    fun dailyLimitIsQuotaExhaustedWithResetFromRetryAfter() {
        val body = err("rate_limit_exceeded", "Rate limit reached for model in organization on tokens per day (TPD): Limit 200000, Used 199990, Requested 500. Please try again in 7m12.5s.")
        val e = parse(429, body, "433").error as LlmError.QuotaExhausted
        assertEquals(now.plusSeconds(433), e.resetAt)
        assertEquals("tokens per day", e.quotaId)
    }

    @Test
    fun dailyLimitFallsBackToTheMessageWhenNoHeader() {
        val body = err("rate_limit_exceeded", "Rate limit reached on requests per day (RPD): Limit 1000. Please try again in 2h3m.")
        val e = parse(429, body).error as LlmError.QuotaExhausted
        assertEquals(now.plusSeconds(7380), e.resetAt)
    }

    @Test
    fun perMinuteLimitIsOrdinaryRateLimiting() {
        val body = err("rate_limit_exceeded", "Rate limit reached on tokens per minute (TPM): Limit 8000. Please try again in 1.5s.")
        assertEquals(LlmError.RateLimited(2L), parse(429, body).error)
        assertEquals(LlmError.RateLimited(5L), parse(429, body, "5").error)
    }

    @Test
    fun unrecognised429IsUnclearNotExhausted() {
        assertEquals(LlmError.Unclear429(null), parse(429, err("rate_limit_exceeded", "slow down")).error)
        assertEquals(LlmError.Unclear429(null), parse(429, "not json").error)
    }

    @Test
    fun authAndModelErrors() {
        assertEquals(LlmError.InvalidKey, parse(401, err("invalid_api_key", "Invalid API Key", "invalid_request_error")).error)
        assertEquals(LlmError.ModelNotFound("openai/gpt-oss-120b"), parse(404, err("model_not_found", "no such model")).error)
        assertEquals(LlmError.ModelNotFound("openai/gpt-oss-120b"), parse(400, err("model_decommissioned", "The model has been decommissioned")).error)
        assertTrue(parse(403, err("forbidden", "blocked")).error is LlmError.PermissionDenied)
        assertTrue(parse(400, err("invalid_request", "bad")).error is LlmError.InvalidRequest)
    }

    @Test
    fun serverErrorsKeepOverloadSeparateFromEverythingElse() {
        assertTrue(parse(503, err("service_unavailable", "Service unavailable", "internal_server_error")).error is LlmError.Overloaded)
        assertTrue(parse(500, err("x", "boom")).error is LlmError.InternalError)
        assertEquals(LlmError.ServerTrouble(502), parse(502, err("x", "bad gateway")).error)
        val html = parse(503, "<html>Cloudflare</html>").error
        assertTrue(html is LlmError.NotFromApi)
        assertFalse((html as LlmError.NotFromApi).snippet.contains("<"))
        assertTrue(parse(403, "<html>error code: 1010</html>").error is LlmError.NotFromApi)
    }

    @Test
    fun durations() {
        assertEquals(733L, GroqParsing.parseDuration("7m12.5s."))
        assertEquals(2L, GroqParsing.parseDuration("1.5s"))
        assertEquals(1L, GroqParsing.parseDuration("340ms"))
        assertEquals(null, GroqParsing.parseDuration("soon"))
        assertEquals(60L, GroqParsing.retryAfterSeconds("60", ""))
        assertEquals(null, GroqParsing.retryAfterSeconds(null, "no hint"))
    }

    @Test
    fun modelListLookup() {
        val body = """{"data":[{"id":"a","active":true},{"id":"openai/gpt-oss-120b","active":true},{"id":"old","active":false}]}"""
        assertEquals("openai/gpt-oss-120b", GroqParsing.findModel(body, "openai/gpt-oss-120b")?.id)
        assertEquals(false, GroqParsing.findModel(body, "old")?.active)
        assertEquals(null, GroqParsing.findModel(body, "missing"))
        assertEquals(null, GroqParsing.findModel("junk", "a"))
    }
}
