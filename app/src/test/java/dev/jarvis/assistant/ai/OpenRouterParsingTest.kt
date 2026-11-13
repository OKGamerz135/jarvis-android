package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OpenRouterParsingTest {
    private val now = Instant.parse("2026-11-13T23:59:59Z")

    @Test fun onlyColonFreeIdsAreFree() {
        assertTrue(OpenRouterParsing.isFreeId("vendor/model:free"))
        assertFalse(OpenRouterParsing.isFreeId("vendor/model"))
        assertFalse(OpenRouterParsing.isFreeId("vendor/model:free-extended"))
        assertFalse(OpenRouterParsing.isFreeId("openrouter/free"))
        assertFalse(OpenRouterParsing.isFreeId(""))
    }

    @Test fun bodyHasSystemPromptHistoryModelAndStreamFlag() {
        val body = OpenRouterParsing.buildBody(
            ChatRequest("m:free", "KEY", "sys", listOf(ChatMessage(ChatRole.USER, "u"), ChatMessage(ChatRole.ASSISTANT, "a"))),
            "m:free", stream = true,
        )
        val o = org.json.JSONObject(body)
        assertEquals("m:free", o.getString("model"))
        assertTrue(o.getBoolean("stream"))
        val roles = (0 until o.getJSONArray("messages").length()).map { o.getJSONArray("messages").getJSONObject(it).getString("role") }
        assertEquals(listOf("system", "user", "assistant"), roles)
        assertFalse(body.contains("KEY"))
    }

    @Test fun streamItemsAreTextErrorOrNothing() {
        assertEquals(OpenRouterParsing.StreamItem.Text("hi"), OpenRouterParsing.streamItem("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertEquals(OpenRouterParsing.StreamItem.Nothing, OpenRouterParsing.streamItem("""{"choices":[{"delta":{"content":null}}]}"""))
        assertEquals(OpenRouterParsing.StreamItem.Nothing, OpenRouterParsing.streamItem("not json"))
        assertEquals(
            OpenRouterParsing.StreamItem.Error("429", "slow down"),
            OpenRouterParsing.streamItem("""{"error":{"code":429,"message":"slow down"},"choices":[{"delta":{"content":""},"finish_reason":"error"}]}"""),
        )
        assertEquals(
            OpenRouterParsing.StreamItem.Error(null, null),
            OpenRouterParsing.streamItem("""{"choices":[{"delta":{"content":""},"finish_reason":"error"}]}"""),
        )
    }

    @Test fun streamErrorsMapToTypedErrors() {
        fun of(code: String?) = OpenRouterParsing.errorFromItem(OpenRouterParsing.StreamItem.Error(code, "m"), "x:free")
        assertEquals(LlmError.InvalidKey, of("401"))
        assertTrue(of("402") is LlmError.BillingRequired)
        assertTrue(of("403") is LlmError.PermissionDenied)
        assertTrue(of("404") is LlmError.ModelNotFound)
        assertTrue(of("408") is LlmError.Timeout)
        assertTrue(of("502") is LlmError.Overloaded)
        assertTrue(of("provider_unavailable") is LlmError.Overloaded)
        assertTrue(of(null) is LlmError.Other)
        assertTrue(OpenRouterParsing.isRateLimit("429", null))
        assertTrue(OpenRouterParsing.isRateLimit(null, "Rate limit exceeded"))
        assertFalse(OpenRouterParsing.isRateLimit("server_error", "died"))
    }

    @Test fun retryTimeComesFromRetryAfterThenTheResetHeaderOnlyIfClose() {
        assertEquals(5L, OpenRouterParsing.retrySeconds("5", null, now))
        assertEquals(3L, OpenRouterParsing.retrySeconds("2.1", null, now))
        assertEquals(10L, OpenRouterParsing.retrySeconds(null, (now.toEpochMilli() + 10_000).toString(), now))
        assertNull(OpenRouterParsing.retrySeconds(null, (now.toEpochMilli() + 3_600_000).toString(), now)) // an hour away: not a short wait
        assertNull(OpenRouterParsing.retrySeconds(null, (now.toEpochMilli() - 1000).toString(), now))
        assertNull(OpenRouterParsing.retrySeconds(null, null, now))
    }

    @Test fun keyInfoAndUtcMidnight() {
        val q = OpenRouterParsing.parseKey("""{"data":{"free_model_daily_requests":{"used":50,"limit":50,"remaining":0}}}""")
        assertEquals(OpenRouterParsing.FreeQuota(50, 50, 0), q)
        assertNull(OpenRouterParsing.parseKey("""{"data":{}}"""))
        assertNull(OpenRouterParsing.parseKey("nope"))
        assertEquals(Instant.parse("2026-11-14T00:00:00Z"), OpenRouterParsing.nextUtcMidnight(now))
        assertTrue(OpenRouterParsing.describe(q!!).contains("Reported by OpenRouter"))
    }

    @Test fun modelListRulesFreeMeansIdAndEveryPriceZero() {
        val list = """{"data":[
          {"id":"a:free","context_length":10,"pricing":{"prompt":"0","completion":"0","request":"0"}},
          {"id":"b:free","context_length":20,"pricing":{"prompt":"0","completion":"0","request":"0.01"}},
          {"id":"c","context_length":30,"pricing":{"prompt":"0","completion":"0"}},
          {"id":"d:free","context_length":40,"pricing":{"prompt":"0","completion":"0"},"expiration_date":"2026-11-13"},
          {"id":"e:free","context_length":5,"pricing":{"prompt":"0","completion":"0"},"expiration_date":"2026-12-01"},
          {"id":"f:free","context_length":50}
        ]}"""
        val free = OpenRouterParsing.freeModels(list, now).map { it.id }
        assertEquals(listOf("a:free", "e:free"), free)
        assertEquals("a:free", OpenRouterParsing.pickAuto(OpenRouterParsing.freeModels(list, now))!!.id)
        assertTrue(OpenRouterParsing.lookup(list, "b:free", now) is OpenRouterParsing.Lookup.NotFree)
        assertTrue(OpenRouterParsing.lookup(list, "d:free", now) is OpenRouterParsing.Lookup.Expired)
        assertTrue(OpenRouterParsing.lookup(list, "zzz:free", now) is OpenRouterParsing.Lookup.NotListed)
        assertNull(OpenRouterParsing.pickAuto(emptyList()))
    }
}
