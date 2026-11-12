package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverPolicyTest {
    private val G = AiProvider.GEMINI
    private val R = AiProvider.GROQ
    private val quota = LlmError.QuotaExhausted(null, "PerDay", ResetSource.UNKNOWN)

    private fun cand(p: AiProvider, enabled: Boolean = true, key: Boolean = true, ack: Boolean = true, ex: Boolean = false) =
        Candidate(p, "m", enabled, key, ack, ex)

    private fun ctx(
        current: AiProvider = G,
        on: Boolean = true,
        cands: List<Candidate> = listOf(cand(G), cand(R)),
        partial: Boolean = false,
        rate: Boolean = false,
        net: Boolean = false,
        tried: Set<AiProvider> = setOf(current),
    ) = FailoverContext(current, on, cands, partial, rate, net, tried)

    @Test fun quotaSwitchesToNextEligible() {
        val d = FailoverPolicy.decide(quota, ctx())
        assertTrue(d is Decision.Switch)
        assertEquals(R, (d as Decision.Switch).to.provider)
    }

    @Test fun failoverOffNeverSwitches() {
        assertEquals(Decision.FailoverOff, FailoverPolicy.decide(quota, ctx(on = false)))
    }

    @Test fun partialStreamingNeverSwitchesOrRetries() {
        assertEquals(Decision.ShowError, FailoverPolicy.decide(quota, ctx(partial = true)))
        assertEquals(Decision.ShowError, FailoverPolicy.decide(LlmError.RateLimited(2), ctx(partial = true)))
        assertEquals(Decision.ShowError, FailoverPolicy.decide(LlmError.Offline, ctx(partial = true)))
    }

    @Test fun ineligibleTargetsAreExplained() {
        for ((c, word) in listOf(
            cand(R, enabled = false) to "turned off",
            cand(R, key = false) to "no API key",
            cand(R, ack = false) to "understand",
            cand(R, ex = true) to "quota",
        )) {
            val d = FailoverPolicy.decide(quota, ctx(cands = listOf(cand(G), c)))
            assertTrue(d is Decision.NoAlternative)
            assertTrue((d as Decision.NoAlternative).reasons.single().second.contains(word))
        }
    }

    @Test fun neverReturnsToAProviderAlreadyTriedForThisMessage() {
        val d = FailoverPolicy.decide(quota, ctx(current = R, tried = setOf(G, R)))
        assertTrue(d is Decision.NoAlternative)
    }

    @Test fun shortRateLimitWaitsOnceLongOrRepeatedDoesNot() {
        assertEquals(Decision.WaitRateLimit(5), FailoverPolicy.decide(LlmError.RateLimited(5), ctx()))
        assertEquals(Decision.WaitRateLimit(1), FailoverPolicy.decide(LlmError.RateLimited(0), ctx()))
        assertEquals(Decision.WaitRateLimit(60), FailoverPolicy.decide(LlmError.RateLimited(60), ctx()))
        assertEquals(Decision.ShowError, FailoverPolicy.decide(LlmError.RateLimited(61), ctx()))
        assertEquals(Decision.ShowError, FailoverPolicy.decide(LlmError.RateLimited(null), ctx()))
        assertEquals(Decision.ShowError, FailoverPolicy.decide(LlmError.RateLimited(5), ctx(rate = true)))
    }

    @Test fun rateLimitNeverSwitchesProvider() {
        assertTrue(FailoverPolicy.decide(LlmError.RateLimited(5), ctx()) !is Decision.Switch)
    }

    @Test fun offlineWaitsOnce() {
        assertEquals(Decision.WaitForNetwork, FailoverPolicy.decide(LlmError.Offline, ctx()))
        assertEquals(Decision.ShowError, FailoverPolicy.decide(LlmError.Offline, ctx(net = true)))
    }

    @Test fun otherErrorsNeverSwitch() {
        for (e in listOf(LlmError.Overloaded(null), LlmError.InvalidKey, LlmError.NoKey, LlmError.NotAcknowledged)) {
            assertEquals(Decision.ShowError, FailoverPolicy.decide(e, ctx()))
        }
        assertEquals(Decision.ShowError, FailoverPolicy.decide(null, ctx()))
    }
}
