package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun onlyOverloadIsRetried() {
        assertTrue(RetryPolicy.shouldRetry(LlmError.Overloaded(null)))
        val others = listOf(
            LlmError.InvalidKey, LlmError.Offline, LlmError.ModelNotFound("m"), LlmError.RateLimited(1),
            LlmError.QuotaExhausted(null, "q"), LlmError.Unclear429(null), LlmError.InternalError(null),
            LlmError.Timeout(null), LlmError.ServerTrouble(503), LlmError.NotFromApi(503, ""),
            LlmError.BillingRequired(null), LlmError.PermissionDenied(null), LlmError.InvalidRequest(null),
        )
        for (e in others) assertFalse(e.toString(), RetryPolicy.shouldRetry(e))
    }

    @Test
    fun backoffDoublesWithBoundedJitter() {
        repeat(50) {
            assertTrue(RetryPolicy.delayBeforeRetry(1) in 2000L..2500L)
            assertTrue(RetryPolicy.delayBeforeRetry(2) in 4000L..4500L)
        }
        assertEquals(2000L, RetryPolicy.BASE_DELAY_MS)
    }
}
