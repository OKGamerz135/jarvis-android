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
    ) = ProviderStatusModel.of(p.toId(), ProviderDescriptor.of(p).label, key, ack, tests, preferred.toId(), from?.toId())

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

    // ------------------------------------------------------------------ labels for the Choose Chat Provider tab

    @Test fun badgesShowPreferredAndActiveSeparately() {
        assertEquals(listOf("Preferred", "Active"), status(p = G, preferred = G).chatRoleBadges())
        assertEquals(emptyList<String>(), status(p = Q, preferred = G).chatRoleBadges())
    }

    @Test fun badgesDuringAnAutomaticSwitch() {
        assertEquals(listOf("Preferred (paused by automatic failover)"), status(p = G, preferred = Q, from = G).chatRoleBadges())
        assertEquals(listOf("Active via automatic failover"), status(p = Q, preferred = Q, from = G).chatRoleBadges())
        assertEquals(emptyList<String>(), status(p = C, preferred = Q, from = G).chatRoleBadges())
    }

    @Test fun aBlockedProviderIsNotLabelledActive() {
        val b = status(p = G, key = KeyState.MISSING, preferred = G).chatRoleBadges()
        assertEquals(listOf("Preferred", "Selected for chat, but can't answer yet"), b)
    }

    @Test fun badgesNeverMentionTesting() {
        for (t in listOf(none, none.copy(generation = genDone)))
            assertTrue(status(p = G, preferred = G, tests = t).chatRoleBadges().none { it.contains("test", ignoreCase = true) })
    }

    // ------------------------------------------------------------------ Configure & Test AI tab text

    @Test fun keyLabelDescribesOnlyTheKey() {
        assertEquals("No API key saved", status(key = KeyState.MISSING).keyLabel())
        assertTrue(status(key = KeyState.UNREADABLE_NOW).keyLabel().contains("can't be read"))
        assertTrue(status().keyLabel().startsWith("API key saved"))
        // Missing consent doesn't make the key look missing.
        assertTrue(status(ack = false).keyLabel().startsWith("API key saved"))
    }

    @Test fun nothingTestedMeansNoDetailLines() {
        assertEquals(emptyList<String>(), ProviderStatusModel.testDetails(none))
    }

    @Test fun detailsReportEachCheckHonestly() {
        val d = ProviderStatusModel.testDetails(
            none.copy(availability = availOk, generation = TestState.Failed("quota exceeded", null), diagnostics = DiagState.Failed("no network")),
        )
        assertEquals(
            listOf(
                "Availability check: the key is accepted and model \"m\" exists",
                "Generation test failed: quota exceeded",
                "Diagnostics could not finish: no network",
            ),
            d,
        )
    }

    @Test fun anAvailabilityOkLineNeverClaimsAGenerationReply() {
        val d = ProviderStatusModel.testDetails(none.copy(availability = availOk))
        assertEquals(1, d.size)
        assertFalse(d.any { it.contains("reply") })
    }

    @Test fun aGenerationReplyIsReportedAsAReplyOnly() {
        assertEquals(listOf("Generation test: a reply came back"), ProviderStatusModel.testDetails(none.copy(generation = genDone)))
    }

    @Test fun runningChecksShowAsRunning() {
        val d = ProviderStatusModel.testDetails(none.copy(availability = AvailabilityState.Running, generation = TestState.Running("", null)))
        assertEquals(listOf("Availability check: running", "Generation test: running"), d)
    }

    // ------------------------------------------------------------------ Settings hub summary

    private fun hub(
        preferred: AiProvider = G, from: AiProvider? = null, failover: Boolean = true,
        keys: Map<AiProvider, KeyState> = emptyMap(), ack: Set<AiProvider> = AiProvider.entries.toSet(),
        tests: Map<AiProvider, ProviderTestState> = emptyMap(),
    ) = ProviderStatusModel.hubSummary(
        ProviderStatusModel.all(
            ProviderRegistry.withBuiltIns().all(),
            AiProvider.entries.associate { it.toId() to (keys[it] ?: KeyState.USABLE) },
            ack.map { it.toId() }.toSet(),
            tests.mapKeys { it.key.toId() },
            preferred.toId(),
            from?.toId(),
        ).values,
        failover,
    )

    @Test fun aSavedKeyIsConfiguredNotReadyAndNotTested() {
        val t = hub()
        assertEquals("Preferred: ${G.label}, configured, not tested. Failover on.", t)
        assertFalse(t.contains("ready", ignoreCase = true)); assertFalse(t.contains("passed"))
    }

    @Test fun onlyARealPassedTestSaysPassed() {
        val t = hub(tests = mapOf(G to none.copy(generation = genDone)))
        assertEquals("Preferred: ${G.label}, configured, test passed. Failover on.", t)
        assertTrue(hub(tests = mapOf(G to none.copy(availability = availOk))).contains("generation not tested"))
        assertTrue(hub(tests = mapOf(G to none.copy(generation = TestState.Failed("x", null)))).contains("last test failed"))
    }

    @Test fun missingUnreadableAndConsentProblemsAreNamed() {
        assertTrue(hub(keys = mapOf(G to KeyState.MISSING)).contains("no API key"))
        assertTrue(hub(keys = mapOf(G to KeyState.UNREADABLE_NOW)).contains("key can't be read"))
        assertTrue(hub(ack = emptySet()).contains("needs your consent"))
    }

    @Test fun aBlockedPreferredProviderSaysChatCannotAnswerAndNeverMentionsTests() {
        val t = hub(keys = mapOf(G to KeyState.MISSING), tests = mapOf(G to none.copy(generation = genDone)))
        assertEquals("Preferred: ${G.label}, no API key. Chat can't answer until this is fixed. Failover on.", t)
    }

    @Test fun duringAnAutomaticSwitchPreferredAndAnsweringNowAreSeparate() {
        val t = hub(preferred = Q, from = G)
        assertEquals(
            "Preferred: ${G.label} (paused). Answering now: ${Q.label} (automatic failover, configured, not tested). Failover on.", t,
        )
    }

    @Test fun failoverStateIsShown() {
        assertTrue(hub(failover = false).endsWith("Failover off."))
        assertTrue(hub(failover = true).endsWith("Failover on."))
    }

    @Test fun theSummaryIsShortEnoughForACard() {
        for (case in listOf(hub(), hub(preferred = Q, from = G), hub(keys = mapOf(G to KeyState.MISSING))))
            assertTrue(case, case.length < 140)
    }

    @Test fun theSharedBuilderGivesEveryProviderExactlyOneModelWithTheSameRules() {
        val all = ProviderStatusModel.all(ProviderRegistry.withBuiltIns().all(), emptyMap(), emptySet(), emptyMap(), G.toId(), null)
        assertEquals(AiProvider.entries.map { it.toId() }.toSet(), all.keys)
        assertTrue(all.values.all { it.config == ConfigStatus.NO_KEY && it.test == TestStatus.NOT_TESTED })
        assertEquals(1, all.values.count { it.preferred })
    }

    // ------------------------------------------------------------------ C2c: ids and labels

    @Test fun theLabelIsWhateverTheCallerSuppliedAndTheModelLooksNothingUp() {
        val m = ProviderStatusModel.of(Q.toId(), "Any name at all", KeyState.USABLE, true, none, G.toId())
        assertEquals("Any name at all", m.label)
        assertEquals(Q.toId(), m.id)
    }

    @Test fun builtInLabelsFromTheDescriptorsAreTheOnesTheScreensAlwaysShowed() {
        val all = ProviderStatusModel.all(ProviderRegistry.withBuiltIns().all(), emptyMap(), emptySet(), emptyMap(), G.toId(), null)
        for (p in AiProvider.entries) assertEquals(p.label, all.getValue(p.toId()).label)
    }

    @Test fun consentGivenForOneProviderIsNotConsentForAnother() {
        val all = ProviderStatusModel.all(
            ProviderRegistry.withBuiltIns().all(),
            keyStates = AiProvider.entries.associate { it.toId() to KeyState.USABLE },
            acknowledged = setOf(Q.toId()),
            tests = emptyMap(), savedPreferred = G.toId(), failoverFrom = null,
        )
        assertEquals(ConfigStatus.CONFIGURED, all.getValue(Q.toId()).config)
        for (p in AiProvider.entries - Q) assertEquals(p.name, ConfigStatus.NEEDS_CONSENT, all.getValue(p.toId()).config)
    }

    @Test fun oneProvidersKeyAndTestNeverShowUpOnAnother() {
        val all = ProviderStatusModel.all(
            ProviderRegistry.withBuiltIns().all(),
            keyStates = mapOf(Q.toId() to KeyState.USABLE),
            acknowledged = setOf(Q.toId()),
            tests = mapOf(Q.toId() to none.copy(generation = genDone)),
            savedPreferred = G.toId(), failoverFrom = null,
        )
        assertEquals(ConfigStatus.CONFIGURED, all.getValue(Q.toId()).config)
        assertEquals(TestStatus.PASSED, all.getValue(Q.toId()).test)
        for (p in AiProvider.entries - Q) {
            assertEquals(p.name, ConfigStatus.NO_KEY, all.getValue(p.toId()).config)
            assertEquals(p.name, TestStatus.NOT_TESTED, all.getValue(p.toId()).test)
        }
    }

    @Test fun onlyTheProvidersGivenAppearAndACustomOneGetsItsOwnModel() {
        val custom = ProviderDescriptor(ProviderId.newCustom(), "My endpoint", "custom_key_x", "m", "h", "n")
        val all = ProviderStatusModel.all(listOf(ProviderDescriptor.of(G), custom), emptyMap(), emptySet(), emptyMap(), G.toId(), null)
        assertEquals(setOf(G.toId(), custom.id), all.keys)
        assertEquals("My endpoint", all.getValue(custom.id).label)
    }

    @Test fun preferredAndFailoverFollowTheIdsNotTheLabels() {
        // two providers with the same label must still be told apart by id
        val a = ProviderDescriptor(ProviderId.newCustom(), "Same", "k_a", "m", "h", "n")
        val b = ProviderDescriptor(ProviderId.newCustom(), "Same", "k_b", "m", "h", "n")
        val all = ProviderStatusModel.all(listOf(a, b), mapOf(a.id to KeyState.USABLE, b.id to KeyState.USABLE), setOf(a.id, b.id), emptyMap(), a.id, null)
        assertTrue(all.getValue(a.id).active); assertFalse(all.getValue(b.id).active)
        assertTrue(all.getValue(a.id).preferred); assertFalse(all.getValue(b.id).preferred)
    }

    // ------------------------------------------------------------------ C3a: model list unavailable

    private val unverified = AvailabilityState.Unverified("Custom", "m", "no model list")
    private fun doneWith(vararg ok: Boolean?) =
        DiagState.Done(ok.map { dev.jarvis.assistant.ai.DiagLine("x", it, "d", unverified = it == null) }, "v")

    @Test fun anUnavailableModelListAloneIsNotTestedNeitherAcceptedNorPassed() {
        assertEquals(TestStatus.NOT_TESTED, ProviderStatusModel.testStatus(none.copy(availability = unverified)))
    }

    @Test fun anUnavailableListPlusARealGenerationPasses() {
        assertEquals(TestStatus.PASSED, ProviderStatusModel.testStatus(none.copy(availability = unverified, generation = genDone)))
    }

    @Test fun anUnavailableListPlusAFailedGenerationFails() {
        assertEquals(TestStatus.FAILED, ProviderStatusModel.testStatus(none.copy(availability = unverified, generation = TestState.Failed("x", null))))
    }

    @Test fun anUnavailableListWithAFailedDiagnosticsStepFailsButASuccessfulOneDoesNotPass() {
        assertEquals(TestStatus.FAILED, ProviderStatusModel.testStatus(none.copy(availability = unverified, diagnostics = doneWith(null, false, null))))
        assertEquals(TestStatus.NOT_TESTED, ProviderStatusModel.testStatus(none.copy(availability = unverified, diagnostics = doneWith(null, true, true))))
    }

    @Test fun theUnavailableListLineSaysItIsNeitherAPassNorAFailure() {
        val line = ProviderStatusModel.testDetails(none.copy(availability = unverified)).single()
        assertTrue(line, line.contains("not verified") || line.contains("unavailable") || line.contains("could not"))
        assertFalse(line.contains("is accepted") || line.contains("exists"))
    }
}
