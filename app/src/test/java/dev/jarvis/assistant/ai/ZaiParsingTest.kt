package dev.jarvis.assistant.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ZaiParsingTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private fun err(code: String, msg: String = "m") = """{"error":{"code":"$code","message":"$msg"}}"""
    private fun parse(status: Int, body: String, retry: String? = null) = ZaiParsing.parseFailure(status, body, retry, "glm-4.7-flash", now)

    @Test
    fun onlyTheTwoFreeModelsAreAllowed() {
        assertEquals(listOf("glm-4.7-flash", "glm-4.5-flash"), ZaiParsing.FREE_MODELS)
        assertTrue(ZaiParsing.isFreeModel("glm-4.7-flash"))
        assertTrue(ZaiParsing.isFreeModel(" GLM-4.5-Flash "))
        for (paid in listOf("glm-4.7", "glm-5", "glm-5.2", "glm-4.6", "glm-4.7-flashx", "", "glm-4.7-flash-pro")) {
            assertFalse(paid, ZaiParsing.isFreeModel(paid))
        }
        assertTrue(AiProviderDefaultIsFree())
    }

    private fun AiProviderDefaultIsFree() = ZaiParsing.isFreeModel(dev.jarvis.assistant.settings.AiProvider.ZAI.defaultModel)

    @Test
    fun bodyHasSystemPromptHistoryStreamFlagAndThinkingDisabled() {
        val r = ChatRequest("glm-4.7-flash", "K", "SYS", listOf(ChatMessage(ChatRole.USER, "hi"), ChatMessage(ChatRole.ASSISTANT, "yo")))
        val j = JSONObject(ZaiParsing.buildBody(r, true))
        assertEquals("glm-4.7-flash", j.getString("model"))
        assertTrue(j.getBoolean("stream"))
        assertEquals("disabled", j.getJSONObject("thinking").getString("type"))
        val m = j.getJSONArray("messages")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("SYS", m.getJSONObject(0).getString("content"))
        assertEquals("assistant", m.getJSONObject(2).getString("role"))
        assertFalse(JSONObject(ZaiParsing.buildBody(r, false)).getBoolean("stream"))
    }

    @Test
    fun onlyContentIsReadSoReasoningTextIsNeverShown() {
        assertEquals("Hi", ZaiParsing.deltaText("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals("", ZaiParsing.deltaText("""{"choices":[{"delta":{"reasoning_content":"thinking"}}]}"""))
        assertEquals("", ZaiParsing.deltaText("""{"choices":[{"delta":{"content":null}}]}"""))
    }

    @Test
    fun insufficientBalanceIsBillingRequiredAndNeverAQuotaToFailOverFrom() {
        val p = parse(429, err("1113", "Insufficient balance or no resource package. Please recharge."))
        assertTrue(p.error is LlmError.BillingRequired)
        assertEquals("1113", p.apiStatus)
        // numeric code form
        assertTrue(parse(429, """{"error":{"code":1113,"message":"x"}}""").error is LlmError.BillingRequired)
    }

    @Test
    fun rateAndOverloadCodesAreTemporaryNotQuota() {
        assertEquals(LlmError.RateLimited(null), parse(429, err("1302")).error)
        assertEquals(LlmError.RateLimited(7), parse(429, err("1313"), "7").error)
        assertTrue(parse(429, err("1305")).error is LlmError.Overloaded)
    }

    @Test
    fun usageWindowCodesAreGenuineQuotaWithAnUnknownResetNeverGuessed() {
        for (c in listOf("1308", "1310", "1316", "1318", "1321")) {
            val e = parse(429, err(c, "limit reached, resets at 2026-11-15 00:00:00")).error as LlmError.QuotaExhausted
            assertNull(c, e.resetAt)
            assertEquals(ResetSource.UNKNOWN, e.resetSource)
        }
    }

    @Test
    fun aUnrecognised429IsUnclearAndNeverFailsOver() {
        assertTrue(parse(429, err("9999")).error is LlmError.Unclear429)
        assertTrue(parse(429, "not json").error is LlmError.Unclear429)
    }

    @Test
    fun otherCodesMapToTypedErrors() {
        assertEquals(LlmError.InvalidKey, parse(401, err("1000")).error)
        assertEquals(LlmError.InvalidKey, parse(401, err("1003")).error)
        assertTrue(parse(403, err("1220")).error is LlmError.PermissionDenied)
        assertTrue(parse(400, err("1211")).error is LlmError.ModelNotFound)
        assertTrue(parse(400, err("1214")).error is LlmError.InvalidRequest)
        assertTrue(parse(400, err("1261")).error is LlmError.InvalidRequest)
        assertTrue(parse(400, err("1301")).error is LlmError.Blocked)
        assertTrue(parse(500, err("1234")).error is LlmError.InternalError)
        assertTrue(parse(503, "<html>x</html>").error is LlmError.NotFromApi)
    }

    @Test
    fun errorMessagesAreRedacted() {
        val p = ZaiParsing.parseFailure(401, err("1000", "bad key SECRETKEY123456 here"), null, "m", now, listOf("SECRETKEY123456"))
        assertFalse(p.apiMessage!!.contains("SECRETKEY123456"))
    }
}
