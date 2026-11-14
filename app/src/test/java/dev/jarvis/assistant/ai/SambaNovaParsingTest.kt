package dev.jarvis.assistant.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SambaNovaParsingTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private fun err(code: String, msg: String = "m") =
        """{"error":{"message":"$msg","type":"t","param":null,"code":"$code"},"request_id":"r"}"""

    private fun parse(status: Int, body: String, vararg h: Pair<String, String>) =
        SambaNovaParsing.parseFailure(status, body, h.toMap(), "Meta-Llama-3.3-70B-Instruct", now)

    @Test
    fun requestHasSystemPromptHistoryModelAndStreamFlagAndNoReasoningFields() {
        val r = ChatRequest("Meta-Llama-3.3-70B-Instruct", "K", "SYS", listOf(ChatMessage(ChatRole.USER, "hi"), ChatMessage(ChatRole.ASSISTANT, "yo")))
        val j = JSONObject(SambaNovaParsing.buildBody(r, true))
        assertEquals("Meta-Llama-3.3-70B-Instruct", j.getString("model"))
        assertTrue(j.getBoolean("stream"))
        val m = j.getJSONArray("messages")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("SYS", m.getJSONObject(0).getString("content"))
        assertEquals("assistant", m.getJSONObject(2).getString("role"))
        assertFalse(j.has("reasoning_effort"))
        assertFalse(JSONObject(SambaNovaParsing.buildBody(r, false)).getBoolean("stream"))
    }

    @Test
    fun deltaTextReadsOnlyContent() {
        assertEquals("Hi", SambaNovaParsing.deltaText("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals("", SambaNovaParsing.deltaText("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals("", SambaNovaParsing.deltaText("""{"choices":[{"delta":{"content":null}}]}"""))
    }

    @Test
    fun dailyQuotaIsOnlyDeclaredWhenTheDayHeaderSaysZeroLeft() {
        val e = parse(
            429, err("insufficient_quota"),
            "x-ratelimit-remaining-requests-day" to "0", "x-ratelimit-reset-requests-day" to "3600",
            "x-ratelimit-remaining-requests" to "19",
        ).error as LlmError.QuotaExhausted
        assertEquals(now.plusSeconds(3600), e.resetAt)
        assertEquals(ResetSource.REPORTED, e.resetSource)
        assertEquals("requests per day (Meta-Llama-3.3-70B-Instruct)", e.quotaId)
    }

    @Test
    fun dailyQuotaWithUnreadableOrAbsurdResetKeepsTheResetUnknownAndNeverGuesses() {
        for (bad in listOf("soon", "", "99999999999")) {
            val e = parse(
                429, err("insufficient_quota"),
                "x-ratelimit-remaining-requests-day" to "0", "x-ratelimit-reset-requests-day" to bad,
            ).error as LlmError.QuotaExhausted
            assertNull(bad, e.resetAt)
            assertEquals(ResetSource.UNKNOWN, e.resetSource)
        }
        val dur = parse(429, err("insufficient_quota"), "x-ratelimit-remaining-requests-day" to "0", "x-ratelimit-reset-requests-day" to "2h3m")
        assertEquals(now.plusSeconds(7380), (dur.error as LlmError.QuotaExhausted).resetAt)
    }

    @Test
    fun perMinuteLimitIsOrdinaryRateLimitNotQuota() {
        val e = parse(
            429, err("insufficient_quota"),
            "x-ratelimit-remaining-requests" to "0", "x-ratelimit-reset-requests" to "12",
            "x-ratelimit-remaining-requests-day" to "7",
        ).error
        assertEquals(LlmError.RateLimited(12), e)
    }

    @Test
    fun a429WithoutHeadersIsUnclearAndNeverTriggersFailover() {
        assertTrue(parse(429, err("insufficient_quota")).error is LlmError.Unclear429)
        assertTrue(parse(429, "not json").error is LlmError.Unclear429)
        // an explicit "per day" in the text is the only header-less quota signal
        assertTrue(parse(429, err("insufficient_quota", "daily request limit reached")).error is LlmError.QuotaExhausted)
    }

    @Test
    fun queueFullIsTemporaryOverloadNotQuota() {
        assertTrue(parse(429, err("queue_full")).error is LlmError.Overloaded)
    }

    @Test
    fun documentedStatusesMapToTypedErrors() {
        assertEquals(LlmError.InvalidKey, parse(401, err("invalid_authentication")).error)
        assertTrue(parse(400, err("context_length_exceeded")).error is LlmError.InvalidRequest)
        assertTrue(parse(400, err("model_not_found")).error is LlmError.ModelNotFound)
        assertTrue(parse(410, err("model_deprecated")).error is LlmError.ModelNotFound)
        assertTrue(parse(408, err("request_timeout")).error is LlmError.Timeout)
        assertTrue(parse(500, err("internal_server_error")).error is LlmError.InternalError)
        assertTrue(parse(503, err("maintenance")).error is LlmError.Overloaded)
        assertTrue(parse(402, err("x")).error is LlmError.BillingRequired)
        assertTrue(parse(503, "<html>gateway</html>").error is LlmError.NotFromApi)
    }

    @Test
    fun errorMessagesAreRedacted() {
        val p = SambaNovaParsing.parseFailure(401, err("invalid_authentication", "bad key SECRETKEY123456 here"), emptyMap(), "m", now, listOf("SECRETKEY123456"))
        assertFalse(p.apiMessage!!.contains("SECRETKEY123456"))
    }

    @Test
    fun findModelAndSecondsParsing() {
        assertTrue(SambaNovaParsing.findModel("""{"data":[{"id":"a"},{"id":"gpt-oss-120b"}]}""", "gpt-oss-120b"))
        assertFalse(SambaNovaParsing.findModel("""{"data":[{"id":"a"}]}""", "b"))
        assertFalse(SambaNovaParsing.findModel("junk", "b"))
        assertEquals(37L, SambaNovaParsing.secondsUntil("36.2"))
        assertEquals(433L, SambaNovaParsing.secondsUntil("7m12.5s"))
        assertNull(SambaNovaParsing.secondsUntil("-5"))
    }
}
