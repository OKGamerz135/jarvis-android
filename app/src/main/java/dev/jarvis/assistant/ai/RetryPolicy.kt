package dev.jarvis.assistant.ai

import kotlin.random.Random

/**
 * Google's guidance for 503 is to wait and retry with exponential backoff. Free-tier quotas are small and
 * some developers report failed calls still count, so JARVIS retries very little: only for "overloaded"
 * errors, never for quota, key or model errors, and never more than the caller allows.
 */
object RetryPolicy {
    const val BASE_DELAY_MS = 2_000L
    const val JITTER_MS = 500L

    fun shouldRetry(error: LlmError): Boolean = error is LlmError.Overloaded

    /** Retry 1 waits about 2 s, retry 2 about 4 s, each plus up to half a second of jitter. */
    fun delayBeforeRetry(retryNumber: Int, random: Random = Random.Default): Long {
        val exponent = (retryNumber - 1).coerceIn(0, 4)
        return (BASE_DELAY_MS shl exponent) + random.nextLong(0L, JITTER_MS + 1)
    }
}
