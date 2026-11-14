package dev.jarvis.assistant.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MistralParsingTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private fun parse(status: Int, body: String, retry: String? = null) =
        MistralParsing.parseFailure(status, body, retry, "mistral-small-latest", now)

    @Test
    fun bodyHasSystemPromptHistoryAndStreamFlag() {
        val r = ChatRequest("mistral-small-latest", "K", "SYS", listOf(ChatMessage(ChatRole.USER, "hi"), ChatMessage(ChatRole.ASSISTANT, "yo")))
        val j = JSONObject(MistralParsing.buildBody(r, true))
        assertEquals("mistral-small-latest", j.getString("model"))
        assertTrue(j.getBoolean("stream"))
        val m = j.getJSONArray("messages")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("assistant", m.getJSONObject(2).getString("role"))
        assertFalse(JSONObject(MistralParsing.buildBody(r, false)).getBoolean("stream"))
    }

    @Test
    fun deltaTextReadsStringsAndTextPartsOnly() {
        assertEquals("Hi", MistralParsing.deltaText("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals("", MistralParsing.deltaText("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals("", MistralParsing.deltaText("not json"))
        assertEquals(
            "ab",
            MistralParsing.deltaText("""{"choices":[{"delta":{"content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"""),
        )
    }

    @Test
    fun messageTextReadsAWholeReply() {
        assertEquals("ok", MistralParsing.messageText("""{"choices":[{"message":{"content":"ok"}}]}"""))
        assertEquals("", MistralParsing.messageText("{}"))
    }

    @Test
    fun errorsAreClassifiedByStatusAndMessage() {
        assertEquals(LlmError.InvalidKey, parse(401, """{"message":"Unauthorized"}""").error)
        assertTrue(parse(403, """{"message":"forbidden"}""").error is LlmError.PermissionDenied)
        assertTrue(parse(404, """{"message":"Not found"}""").error is LlmError.ModelNotFound)
        assertTrue(parse(422, """{"detail":[{"msg":"bad"}],"message":"bad input"}""").error is LlmError.InvalidRequest)
        assertTrue(parse(500, """{"message":"boom"}""").error is LlmError.InternalError)
        assertTrue(parse(503, """{"message":"busy"}""").error is LlmError.Overloaded)
        assertTrue(parse(502, """{"message":"bad gateway"}""").error is LlmError.ServerTrouble)
        assertTrue(parse(500, "<html>x</html>").error is LlmError.NotFromApi)
    }

    @Test
    fun a429IsOnlyExhaustedQuotaWhenTheMessageSaysMonthly() {
        assertTrue(parse(429, """{"message":"Rate limit exceeded"}""", "3").error is LlmError.RateLimited)
        assertEquals(3L, ((parse(429, """{"message":"Rate limit exceeded"}""", "3").error) as LlmError.RateLimited).retryAfterSeconds)
        assertTrue(parse(429, """{"message":"Service tier capacity exceeded"}""").error is LlmError.Overloaded)
        assertTrue(parse(429, """{"message":"Monthly token limit exceeded"}""").error is LlmError.QuotaExhausted)
        assertTrue(parse(429, """{"message":"something odd"}""").error is LlmError.Unclear429)
        assertTrue(parse(429, "").error is LlmError.Unclear429)
    }

    @Test
    fun nestedErrorObjectIsAlsoRead() {
        val p = parse(429, """{"error":{"message":"Rate limit exceeded","type":"rate_limited"}}""")
        assertTrue(p.error is LlmError.RateLimited)
        assertEquals("rate_limited", p.apiStatus)
    }

    @Test
    fun secretsNeverSurviveInMessages() {
        val p = MistralParsing.parseFailure(401, """{"message":"bad KEY123456789012345 here"}""", null, "m", now, listOf("KEY123456789012345"))
        assertFalse(p.apiMessage.orEmpty().contains("KEY123456789012345"))
        assertNull(null)
    }
}
