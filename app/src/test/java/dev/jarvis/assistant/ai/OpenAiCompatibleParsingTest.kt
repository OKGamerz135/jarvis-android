package dev.jarvis.assistant.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiCompatibleParsingTest {
    private val req = ChatRequest("m-1", "KEY-SECRET-1234567890", "be brief", listOf(ChatMessage(ChatRole.USER, "hi"), ChatMessage(ChatRole.ASSISTANT, "yo"), ChatMessage(ChatRole.USER, "again")))

    @Test fun theRequestBodyHasTheModelTheMessagesInOrderAndTheStreamFlagAndNoKey() {
        for (stream in listOf(true, false)) {
            val body = OpenAiCompatibleParsing.buildBody(req, stream)
            val o = JSONObject(body)
            assertEquals("m-1", o.getString("model")); assertEquals(stream, o.getBoolean("stream"))
            val m = o.getJSONArray("messages")
            assertEquals(listOf("system", "user", "assistant", "user"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
            assertEquals("be brief", m.getJSONObject(0).getString("content"))
            assertEquals(setOf("model", "messages", "stream"), o.keys().asSequence().toSet())
            assertTrue(!body.contains("KEY-SECRET"))
        }
    }

    @Test fun streamedTextIsReadFromDeltaContentOnly() {
        assertEquals("Hel", OpenAiCompatibleParsing.deltaText("""{"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertEquals("", OpenAiCompatibleParsing.deltaText("""{"choices":[{"delta":{"content":null}}]}"""))
        assertEquals("", OpenAiCompatibleParsing.deltaText("""{"choices":[{"delta":{"reasoning":"secret thoughts"}}]}"""))
        assertEquals("", OpenAiCompatibleParsing.deltaText("not json"))
        assertEquals("", OpenAiCompatibleParsing.deltaText("""{"choices":[]}"""))
        try { OpenAiCompatibleParsing.deltaText("""{"error":{"message":"boom"}}"""); fail() } catch (e: LlmException) { assertEquals(LlmError.Other("boom"), e.error) }
    }

    @Test fun aWholeReplyIsReadFromMessageContent() {
        assertEquals("hi", OpenAiCompatibleParsing.messageText("""{"choices":[{"message":{"content":"hi"}}]}"""))
        assertEquals("", OpenAiCompatibleParsing.messageText("""{"choices":[{"message":{"content":null}}]}"""))
        assertEquals("", OpenAiCompatibleParsing.messageText("garbage"))
    }

    @Test fun aModelListIsReadOrIsNotAModelList() {
        assertEquals(listOf("a", "b"), OpenAiCompatibleParsing.modelIds("""{"data":[{"id":"a"},{"id":"b"},{"x":1}]}"""))
        assertEquals(emptyList<String>(), OpenAiCompatibleParsing.modelIds("""{"data":[]}"""))
        for (bad in listOf("", "<html>", """{"models":[]}""", "[]", """{"data":"x"}""")) assertNull("for '$bad'", OpenAiCompatibleParsing.modelIds(bad))
    }

    private fun err(status: Int, body: String = "", retry: String? = null) = OpenAiCompatibleParsing.parseFailure(status, body, retry, "m-1").error
    private val apiBody = """{"error":{"message":"msg","type":"t","code":"c"}}"""

    @Test fun errorsAreClassifiedWithoutAssumingAnyVendor() {
        assertEquals(LlmError.InvalidKey, err(401, apiBody))
        assertEquals(LlmError.BillingRequired("msg"), err(402, apiBody))
        assertEquals(LlmError.PermissionDenied("msg"), err(403, apiBody))
        assertTrue(err(403, "<html>blocked</html>") is LlmError.NotFromApi)
        assertEquals(LlmError.ModelNotFound("m-1"), err(404, apiBody))
        assertEquals(LlmError.InvalidRequest("msg"), err(400, apiBody))
        assertEquals(LlmError.Overloaded("msg"), err(503, apiBody))
        assertTrue(err(503, "gateway") is LlmError.NotFromApi)
        assertEquals(LlmError.InternalError("msg"), err(500, apiBody))
        assertEquals(LlmError.ServerTrouble(502), err(502, apiBody))
        assertTrue(err(418, apiBody) is LlmError.Other)
    }

    @Test fun aTooManyRequestsAnswerIsNeverAnExhaustedQuota() {
        // Even a body that says "per day" must not become QuotaExhausted: that is what lets failover switch providers.
        for (text in listOf("You exceeded your daily limit (RPD), requests per day", "tokens per day (TPD)", "quota exhausted", "")) {
            val e = err(429, """{"error":{"message":"$text"}}""", "30")
            assertTrue("for '$text' got $e", e is LlmError.Unclear429)
            assertEquals(30L, (e as LlmError.Unclear429).retryAfterSeconds)
        }
        assertEquals(LlmError.RateLimited(null), err(429, """{"error":{"message":"Too many requests per minute (RPM)"}}"""))
    }

    @Test fun aKeyEchoedInAnErrorBodyIsRedacted() {
        val key = "KEYKEYKEY-1234567890-abcdef"
        val p = OpenAiCompatibleParsing.parseFailure(401, """{"error":{"message":"bad key $key"}}""", null, "m", listOf(key))
        assertTrue(p.apiMessage.orEmpty(), !p.apiMessage.orEmpty().contains(key))
        val n = OpenAiCompatibleParsing.parseFailure(502, "upstream said $key", null, "m", listOf(key)).error as LlmError.NotFromApi
        assertTrue(!n.snippet.contains(key))
    }
}
