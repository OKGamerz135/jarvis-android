package dev.jarvis.assistant.providers

import dev.jarvis.assistant.ai.AvailabilityState
import dev.jarvis.assistant.ai.DiagState
import dev.jarvis.assistant.ai.ProviderTestState
import dev.jarvis.assistant.ai.TestState
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.KeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four independent facts: configured, tested, preferred, active. None may imply another. */
class ProviderStatusModelTest {
    private val G = AiProvider.GEMINI
    private val Q = AiProvider.GROQ
    private val C = AiProvider.CLOUDFLARE
    private val none = ProviderTestState()
    private val availOk = AvailabilityState.Ok("Groq", "m", null, true)
    private val genDone = TestState.Done("hi", 10, "Groq", "m", 1)

    private fun status(
        p: AiProvider = Q, key: KeyState = KeyState.USABLE, ack: Boolean = true, tests: ProviderTestState = none,
        preferred: AiProvider = G, from: AiProvider? = null,
    ) = ProviderStatusModel.of(p, key, ack, tests, preferred, from)

    // ------------------------------------------------------------------ configured

    @Test fun configurationNeedsAReadableKeyAndConsent() {
        assertEquals(ConfigStatus.NO_KEY, status(key = KeyState.MISSING).config)
        assertEquals(ConfigStatus.NO_KEY, status(key = KeyState.MISSING, ack = false).config) // the key problem is reported first
        assertEquals(ConfigStatus.KEY_UNREADABLE, status(key = KeyState.UNREADABLE_NOW).config)
        assertEquals(ConfigStatus.NEEDS_CONSENT, status(ack = false).config)
        assertEquals(ConfigStatus.CONFIGURED, status().config)
    }

    @Test fun aConfiguredProviderIsNeverShownAsTested() {
        val s = status()
        assertTrue(s.configured)
        assertEquals(TestStatus.NOT_TESTED, s.test)
    }

    // ------------------------------------------------------------------ tested

    @Test fun availabilityAloneIsOnlyAcceptedNotPassed() {
        assertEquals(TestStatus.ACCEPTED, ProviderStatusModel.testStatus(none.copy(availability = availOk)))
    }

    @Test fun onlyARealGenerationReplyCountsAsPassed() {
        assertEquals(TestStatus.PASSED, ProviderStatusModel.testStatus(none.copy(generation = genDone)))
        assertEquals(TestStatus.PASSED, ProviderStatusModel.testStatus(none.copy(availability = availOk, generation = genDone)))
    }

    @Test fun diagnosticsFinishingCannotMakeAProviderPassed() {
        val d = DiagState.Done(emptyList(), "everything looks fine")
        assertEquals(TestStatus.NOT_TESTED, ProviderStatusModel.testStatus(none.copy(diagnostics = d)))
    }

    @Test fun anyFailureWinsOverASuccess() {
        assertEquals(TestStatus.FAILED, ProviderStatusModel.testStatus(none.copy(generation = genDone, availability = AvailabilityState.Failed("x", null))))
        assertEquals(TestStatus.FAILED, ProviderStatusModel.testStatus(none.copy(availability = availOk, generation = TestState.Failed("x", null))))
        assertEquals(TestStatus.FAILED, ProviderStatusModel.testStatus(none.copy(generation = genDone, diagnostics = DiagState.Failed("x"))))
    }

    @Test fun aRunningCheckShowsAsRunningEvenIfOthersFailed() {
        assertEquals(TestStatus.RUNNING, ProviderStatusModel.testStatus(none.copy(generation = TestState.Running("", null), availability = AvailabilityState.Failed("x", null))))
    }

    @Test fun clearedResultsAreNotTestedAgain() {
        // ConnectionTester.invalidate() (key or model change) drops a provider's results, which is the default state.
        assertEquals(TestStatus.NOT_TESTED, ProviderStatusModel.testStatus(ProviderTestState()))
    }

    @Test fun aPassedTestDoesNotMakeAnUnconfiguredProviderConfigured() {
        val s = status(key = KeyState.MISSING, tests = none.copy(generation = genDone))
        assertFalse(s.configured)
    }

    // ------------------------------------------------------------------ preferred and active

    @Test fun preferredAndActiveAreSeparateFromConfiguredAndTested() {
        val s = status(p = G, preferred = G)
        assertTrue(s.preferred); assertTrue(s.active)
        val other = status(p = Q, preferred = G)
        assertFalse(other.preferred); assertFalse(other.active)
    }

    @Test fun aPreferredProviderWithoutAKeyIsBlockedNotActive() {
        val s = status(p = G, key = KeyState.MISSING, preferred = G)
        assertTrue(s.preferred); assertFalse(s.active); assertTrue(s.chatBlocked)
        assertFalse(status(p = Q, key = KeyState.MISSING, preferred = G).chatBlocked) // only the selected one is blocked
    }

    @Test fun preferredWithoutConsentIsAlsoBlocked() {
        val s = status(p = G, ack = false, preferred = G)
        assertTrue(s.chatBlocked); assertFalse(s.active)
    }

    @Test fun duringAnAutomaticSwitchPreferredIsTheOriginalChoiceAndActiveIsTheSwitchTarget() {
        // The user chose Gemini; failover moved to Groq and saved it. Cloudflare is unrelated.
        val g = status(p = G, preferred = Q, from = G)
        val q = status(p = Q, preferred = Q, from = G)
        val c = status(p = C, preferred = Q, from = G)
        assertTrue(g.preferred); assertFalse(g.active); assertFalse(g.viaFailover)
        assertFalse(q.preferred); assertTrue(q.active); assertTrue(q.viaFailover)
        assertFalse(c.preferred || c.active || c.viaFailover)
    }

    @Test fun withoutAnEpisodeNothingIsViaFailover() {
        assertFalse(status(p = Q, preferred = Q).viaFailover)
        assertFalse(status(p = Q, preferred = Q, from = Q).viaFailover) // "from" equal to the saved one is not an episode
    }

    @Test fun exactlyOneProviderIsActiveAndExactlyOneIsPreferred() {
        for (episode in listOf<AiProvider?>(null, G)) {
            val all = AiProvider.entries.map { status(p = it, preferred = Q, from = episode) }
            assertEquals(1, all.count { it.active })
            assertEquals(1, all.count { it.preferred })
        }
    }
}
