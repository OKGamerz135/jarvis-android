package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CloudflareParsingTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val id = "0123456789abcdef0123456789abcdef"

    @Test fun credentialsNeedA32HexAccountIdAColonAndAToken() {
        assertEquals(CloudflareParsing.Credentials(id, "tok_en-1"), CloudflareParsing.credentials("  $id:tok_en-1 "))
        assertNull(CloudflareParsing.credentials("tok_only"))
        assertNull(CloudflareParsing.credentials("short:tok"))
        assertNull(CloudflareParsing.credentials("$id:"))
        assertNull(CloudflareParsing.credentials("$id:two words"))
        assertNull(CloudflareParsing.credentials(":tok"))
        assertFalse(CloudflareParsing.BAD_KEY_MESSAGE.contains(id))
    }

    @Test fun streamedChunksInBothShapes() {
        assertEquals(CloudflareParsing.Item.Text("hi"), CloudflareParsing.streamItem("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertEquals(CloudflareParsing.Item.Text("New"), CloudflareParsing.streamItem("""{"response":"New"}"""))
        assertEquals(CloudflareParsing.Item.Nothing, CloudflareParsing.streamItem("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals(CloudflareParsing.Item.Nothing, CloudflareParsing.streamItem("garbage"))
        assertEquals(
            CloudflareParsing.Item.Error("3036", "used up"),
            CloudflareParsing.streamItem("""{"success":false,"errors":[{"code":3036,"message":"used up"}],"result":null}"""),
        )
        assertEquals(CloudflareParsing.Item.Error("500", "boom"), CloudflareParsing.streamItem("""{"error":{"code":500,"message":"boom"}}"""))
    }

    @Test fun wholeReplyShapes() {
        assertEquals(CloudflareParsing.Item.Text("a"), CloudflareParsing.messageResult("""{"choices":[{"message":{"content":"a"}}]}"""))
        assertEquals(CloudflareParsing.Item.Text("b"), CloudflareParsing.messageResult("""{"result":{"response":"b"},"success":true}"""))
        assertEquals(CloudflareParsing.Item.Nothing, CloudflareParsing.messageResult("""{"choices":[{"message":{"content":null}}]}"""))
    }

    @Test fun freeAllocationUsedUpIsDailyQuotaWithADocumentedUtcMidnightReset() {
        val e = CloudflareParsing.classify(429, "3036", "You have used up your daily free allocation of 10,000 neurons.", "m", now) as LlmError.QuotaExhausted
        assertEquals(ResetSource.DOCUMENTED, e.resetSource)
        assertEquals(Instant.parse("2026-11-14T00:00:00Z"), e.resetAt)
        assertEquals("free-neurons-per-day", e.quotaId)
        // The message alone is enough too.
        assertTrue(CloudflareParsing.classify(429, null, "daily free allocation used", "m", now) is LlmError.QuotaExhausted)
    }

    @Test fun otherCodesAreClassifiedWithoutEverCountingAsQuota() {
        fun c(status: Int?, code: String?) = CloudflareParsing.classify(status, code, "msg", "m", now)
        assertTrue(c(429, "3040") is LlmError.Overloaded) // out of capacity: temporary
        assertTrue(c(429, null) is LlmError.Unclear429)
        assertTrue(c(403, "5035") is LlmError.BillingRequired) // model needs Workers Paid
        assertTrue(c(400, "5007") is LlmError.ModelNotFound)
        assertTrue(c(404, "3042") is LlmError.ModelNotFound)
        assertTrue(c(410, "5028") is LlmError.ModelNotFound)
        assertEquals(LlmError.InvalidKey, c(403, "10000"))
        assertEquals(LlmError.InvalidKey, c(401, null))
        assertTrue(c(403, "5016") is LlmError.PermissionDenied)
        assertTrue(c(408, "3007") is LlmError.Timeout)
        assertTrue(c(503, null) is LlmError.Overloaded)
        assertTrue(c(500, null) is LlmError.InternalError)
        assertTrue(c(502, null) is LlmError.ServerTrouble)
        assertTrue(c(400, null) is LlmError.InvalidRequest)
    }

    @Test fun nonApiServerErrorsAreFlaggedAndSecretsAreRedacted() {
        assertTrue(CloudflareParsing.parseFailure(502, "<html>bad</html>", "m", now).error is LlmError.NotFromApi)
        val p = CloudflareParsing.parseFailure(
            403, """{"success":false,"errors":[{"code":5016,"message":"token SECRETTOKEN refused"}]}""", "m", now, listOf("SECRETTOKEN"),
        )
        assertFalse(p.apiMessage!!.contains("SECRETTOKEN"))
        assertEquals("5016", p.apiStatus)
    }

    @Test fun modelSearchNamesAreReadTolerantly() {
        assertEquals(listOf("@cf/a", "@cf/b"), CloudflareParsing.modelNames("""{"result":[{"name":"@cf/a"},{"id":"@cf/b"},{}]}"""))
        assertTrue(CloudflareParsing.modelNames("nope").isEmpty())
    }
}
