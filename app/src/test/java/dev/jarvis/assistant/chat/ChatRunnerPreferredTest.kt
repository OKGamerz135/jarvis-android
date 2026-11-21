package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.toAiProviderOrNull
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The chat provider and automatic failover (D3, C-04) and requests through unconfigured providers (D4, C-06).
 * The saved preferred provider is the single truth about which provider answers.
 */
class ChatRunnerPreferredTest {
    private val G = AiProvider.GEMINI
    private val R = AiProvider.GROQ
    private val C = AiProvider.CLOUDFLARE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = FileChatStorage(Files.createTempDirectory("pref").toFile())
    private var now = 1_000_000L
    private val ledger = newLedger { now }

    @After fun tearDown() = scope.cancel()

    private class Fake(val block: suspend kotlinx.coroutines.flow.FlowCollector<String>.() -> Unit) : LlmProvider {
        val calls = AtomicInteger(0)
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> { calls.incrementAndGet(); return flow { block() } }
    }

    private fun quota() = LlmException(LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 3_600_000), "PerDay", ResetSource.REPORTED))
    private fun ok(text: String) = Fake { emit(text) }
    private fun failing(e: LlmException) = Fake { throw e }

    private fun runner(source: ProviderSource) = ChatRunner(
        storage, ChatEngine(storage), scope, Dispatchers.IO, source, ledger, FakeNetwork(),
        clock = { now }, countdownSeconds = 1, tickMs = 10,
    )

    private suspend fun ChatRunner.idle() = withTimeout(5000) { state.first { it.loaded && !it.generating } }
    /** Sends and waits for the whole outcome: an answer (user message + reply) or a problem, with no countdown or reply still running. */
    private suspend fun ChatRunner.ask(text: String): ChatUiState {
        val before = state.value.conversation.messages.size
        withTimeout(5000) { while (send(text) != SendResult.ACCEPTED) delay(5) } // BUSY while the previous reply is still winding down
        return withTimeout(5000) { state.first { !it.generating && it.failover == null && (it.conversation.messages.size >= before + 2 || it.problem != null) } }
    }
    private suspend fun ChatRunner.until(c: (ChatUiState) -> Boolean) = withTimeout(5000) { state.first(c) }

    // ------------------------------------------------------------------ D3: failover updates the preferred provider

    @Test fun successfulSwitchSavesTheNewPreferredProviderAndLaterMessagesStartThere() = runBlocking {
        val g = failing(quota()); val r = ok("from groq")
        val src = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val run = runner(src)
        run.idle(); run.ask("one")
        assertEquals(listOf(R), src.savedPreferred.toList())
        assertEquals(R, src.preferred)
        val s = run.idle()
        assertEquals(R.toId(), s.temporaryProvider)
        assertEquals("the question about switching back is about the ORIGINAL provider", G.toId(), s.preferredProvider)

        run.ask("two")
        assertEquals("the exhausted provider is not asked again", 1, g.calls.get())
        assertEquals(2, r.calls.get())
        assertEquals(listOf("one", "from groq", "two", "from groq"), run.state.value.conversation.messages.map { it.text })
    }

    @Test fun cancellingTheCountdownSavesNothing() = runBlocking {
        val g = failing(quota()); val r = ok("x")
        val src = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val run = ChatRunner(storage, ChatEngine(storage), scope, Dispatchers.IO, src, ledger, FakeNetwork(), clock = { now }, countdownSeconds = 50, tickMs = 20)
        run.idle(); run.send("hi")
        run.until { it.failover != null }
        run.cancelFailover()
        run.idle()
        assertTrue(src.savedPreferred.isEmpty())
        assertEquals(G, src.preferred)
        assertNull(run.state.value.temporaryProvider)
    }

    @Test fun twoSwitchesInARowKeepTheOriginalProviderForSwitchingBackAndDoNotLoop() = runBlocking {
        val g = failing(quota()); val r = failing(quota()); val c = ok("from cloudflare")
        val src = FakeSource(mapOf(G to g, R to r, C to c), preferred = G).also { it.ledger = ledger }
        val run = runner(src)
        run.idle(); val s = run.ask("hi")
        assertEquals(listOf(R, C), src.savedPreferred.toList())
        assertEquals(C, src.preferred)
        assertEquals(C.toId(), s.temporaryProvider)
        assertEquals(G.toId(), s.preferredProvider)
        assertEquals(1, g.calls.get()); assertEquals(1, r.calls.get()); assertEquals(1, c.calls.get())
    }

    @Test fun noAlternativeLeavesThePreferredProviderUntouched() = runBlocking {
        val g = failing(quota())
        val src = FakeSource(mapOf(G to g), preferred = G).also { it.ledger = ledger }
        val run = runner(src)
        run.idle(); val s = run.ask("hi")
        assertTrue(src.savedPreferred.isEmpty())
        assertEquals(G, src.preferred)
        assertNull(s.temporaryProvider)
        assertTrue(s.problem!!.message.contains("No other provider is ready"))
    }

    @Test fun acceptingTheSwitchBackSavesTheOriginalProviderAgain() = runBlocking {
        val g = failing(quota()); val r = ok("x")
        val src = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val run = runner(src)
        run.idle(); run.ask("hi")
        now += 4_000_000 // the quota reset time has passed
        run.checkRecovery()
        run.until { it.recoveryOffer == G.toId() }
        run.acceptRecovery()
        val s = run.until { it.temporaryProvider == null }
        assertEquals(listOf(R, G), src.savedPreferred.toList())
        assertEquals(G, src.preferred)
        assertNull(s.preferredProvider); assertNull(s.recoveryOffer)
    }

    // ------------------------------------------------------------------ C-04: the user's own choice ends a switch

    @Test fun choosingAPreferredProviderEndsTheSwitchAndTheNextMessageGoesThere() = runBlocking {
        val g = failing(quota()); val r = ok("from groq"); val c = ok("from cloudflare")
        val src = FakeSource(mapOf(G to g, R to r, C to c), preferred = G).also { it.ledger = ledger }
        val run = runner(src)
        run.idle(); run.ask("one")
        assertEquals(R.toId(), run.state.value.temporaryProvider)

        run.choosePreferred(C.toId())
        val s = run.until { it.temporaryProvider == null }
        assertNull(s.preferredProvider); assertNull(s.recoveryOffer)
        assertEquals(C, src.preferred)

        run.ask("two")
        assertEquals(1, c.calls.get())
        assertEquals("the old temporary provider is not used any more", 1, r.calls.get())
        assertEquals("from cloudflare", run.state.value.conversation.messages.last().text)
    }

    @Test fun aPreferenceChangedBehindTheRunnersBackAlsoEndsTheSwitch() = runBlocking {
        val g = failing(quota()); val r = ok("from groq"); val c = ok("from cloudflare")
        val src = FakeSource(mapOf(G to g, R to r, C to c), preferred = G).also { it.ledger = ledger }
        val run = runner(src)
        run.idle(); run.ask("one")
        run.until { it.temporaryProvider == R.toId() }
        src.preferred = C // changed by some other part of the app, not through the runner
        val s = run.ask("two")
        assertEquals(1, c.calls.get())
        assertEquals(1, r.calls.get())
        assertNull(s.temporaryProvider); assertNull(s.preferredProvider)
    }

    // ------------------------------------------------------------------ D4 / C-06: no request through an unconfigured provider

    /** The real resolver over fake settings: the preferred provider has no key, two others are fully configured. */
    private fun realSource(preferred: AiProvider, keyed: Set<AiProvider>, llms: Map<AiProvider, LlmProvider>, failover: Boolean = true) =
        SettingsProviderSource(
            registry = ProviderRegistry.withBuiltIns(),
            settings = { JarvisSettings(provider = preferred, acknowledged = AiProvider.entries.toSet(), failoverEnabled = failover) },
            readKey = { id -> id.toAiProviderOrNull()?.takeIf { it in keyed }?.let { "key-${it.name}" } },
            hasKey = { id -> id.toAiProviderOrNull() in keyed },
            llmFor = { id -> id.toAiProviderOrNull()?.let { llms.getValue(it) } },
            ledger = ledger,
            savePreferred = {},
        )

    @Test fun anUnconfiguredPreferredProviderSendsNothingAnywhereAndNeverFallsBack() = runBlocking {
        val g = ok("gemini"); val r = ok("groq"); val c = ok("cloudflare")
        val run = runner(realSource(preferred = R, keyed = setOf(G, C), llms = mapOf(G to g, R to r, C to c)))
        run.idle(); val s = run.ask("hello")
        assertEquals(0, g.calls.get() + r.calls.get() + c.calls.get())
        val problem = s.problem!!
        assertTrue(problem.needsSetup)
        assertTrue(problem.message, problem.message.contains("Groq is not configured"))
        assertTrue(problem.message.contains("did not switch to another provider"))
        assertNull(s.temporaryProvider)
        assertEquals("the user's message is kept so Retry works after setup", listOf("hello"), s.conversation.messages.map { it.text })
    }

    @Test fun afterTheKeyIsAddedRetryGoesThroughThatProvider() = runBlocking {
        val r = ok("groq answer")
        val keyed = java.util.concurrent.CopyOnWriteArraySet(setOf(G))
        val src = SettingsProviderSource(
            registry = ProviderRegistry.withBuiltIns(),
            settings = { JarvisSettings(provider = R, acknowledged = AiProvider.entries.toSet()) },
            readKey = { id -> id.toAiProviderOrNull()?.takeIf { it in keyed }?.let { "k" } },
            hasKey = { false }, llmFor = { r }, ledger = ledger, savePreferred = {},
        )
        val run = runner(src)
        run.idle(); run.ask("hello")
        assertEquals(0, r.calls.get())
        keyed += R
        withTimeout(5000) { while (run.retry() != SendResult.ACCEPTED) delay(5) }; run.until { !it.generating && it.conversation.messages.size >= 2 }
        assertEquals(1, r.calls.get())
        assertEquals("groq answer", run.state.value.conversation.messages.last().text)
    }

    @Test fun anUnconfiguredPreferredProviderIsNotRescuedEvenWhenFailoverIsOff() = runBlocking {
        val g = ok("gemini")
        val run = runner(realSource(preferred = R, keyed = setOf(G), llms = mapOf(G to g, R to ok("x")), failover = false))
        run.idle(); run.ask("hello")
        assertEquals(0, g.calls.get())
    }

    // ------------------------------------------------------------------ a message sent while a preference change is still being saved

    /** Wraps a source so that saving the preferred provider takes as long as the test wants. */
    private class SlowSave(val inner: FakeSource, val gate: kotlinx.coroutines.CompletableDeferred<Unit>) : ProviderSource by inner {
        override suspend fun setPreferred(provider: ProviderId) { gate.await(); inner.setPreferred(provider) }
    }

    @Test fun aMessageSentWhileSwitchBackIsStillBeingSavedGoesToTheProviderJustChosen() = runBlocking {
        val g = Fake { emit("from gemini") }; val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = R).also { it.ledger = ledger }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val run = runner(SlowSave(fake, gate))
        run.idle()
        // Start from "the preferred provider is Groq, Gemini was the original" without a real failover:
        run.choosePreferred(G.toId())               // the save is held back by the gate
        val sent = async { run.ask("hello") } // sent while the save is still in progress
        delay(150)
        assertEquals("nothing may be sent through the old provider meanwhile", 0, r.calls.get() + g.calls.get())
        gate.complete(Unit)
        sent.await()
        assertEquals(1, g.calls.get())
        assertEquals(0, r.calls.get())
        assertEquals("from gemini", run.state.value.conversation.messages.last().text)
    }

    // ------------------------------------------------------------------ a preference that can't be saved

    /** A source whose saving of the preferred provider fails (as long as [failing] is true). */
    private class FailingSave(val inner: FakeSource) : ProviderSource by inner {
        @Volatile var failing = true
        val attempts = CopyOnWriteArrayList<ProviderId>()
        override suspend fun setPreferred(provider: ProviderId) {
            attempts += provider
            if (failing) throw java.io.IOException("disk full")
            inner.setPreferred(provider)
        }
    }

    @Test fun failoverThatCannotBeSavedDoesNotSwitchAndNothingIsSentToTheOtherProvider() = runBlocking {
        val g = failing(quota()); val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val src = FailingSave(fake)
        val run = runner(src)
        run.idle()
        val s = run.ask("hello")
        assertEquals(listOf(R.toId()), src.attempts.toList())
        assertEquals("nothing may be sent through the provider that could not be saved", 0, r.calls.get())
        assertNull("the app must not claim a switch that is not saved", s.temporaryProvider)
        assertNull(s.preferredProvider)
        assertEquals("the saved preference is unchanged", G, fake.preferred)
        val p = s.problem!!
        assertTrue(p.message, p.message.contains("couldn't save") && p.message.contains("did not switch") && p.message.contains("nothing was sent to Groq"))
        assertEquals("the user's message is kept", listOf("hello"), s.conversation.messages.map { it.text })
    }

    @Test fun afterAFailedFailoverSaveTheNextMessageStillUsesThePersistedPreferenceAndRecoversWhenSavingWorks() = runBlocking {
        val g = failing(quota()); val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val src = FailingSave(fake)
        val run = runner(src)
        run.idle(); run.ask("one")
        // Still failing: the retry is routed from the persisted preference (Gemini) again, and Groq still gets nothing.
        withTimeout(5000) { while (run.retry() != SendResult.ACCEPTED) delay(5) }
        withTimeout(5000) { while (src.attempts.size < 2) delay(5) } // the second attempt to save the switch failed again
        run.until { !it.generating && it.failover == null }
        assertEquals(0, r.calls.get())
        assertEquals(G, fake.preferred)
        // Saving works again: the switch now happens and is consistent.
        src.failing = false
        withTimeout(5000) { while (run.retry() != SendResult.ACCEPTED) delay(5) }
        val s = run.until { it.temporaryProvider == R.toId() && !it.generating }
        assertEquals(R, fake.preferred); assertEquals(1, r.calls.get()); assertEquals(G.toId(), s.preferredProvider)
    }

    @Test fun manualPreferredChoiceThatCannotBeSavedChangesNothingAndSaysSo() = runBlocking {
        val g = ok("from gemini"); val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val src = FailingSave(fake)
        val run = runner(src)
        run.idle()
        run.choosePreferred(R.toId())
        val s = run.until { it.preferenceError != null }
        assertTrue(s.preferenceError!!.contains("Couldn't save Groq"))
        assertEquals(G, fake.preferred)
        run.ask("hello")
        assertEquals("the next message uses the saved preference, not the unsaved choice", 1, g.calls.get())
        assertEquals(0, r.calls.get())
        // A later successful choice clears the error and takes effect.
        src.failing = false
        run.choosePreferred(R.toId())
        run.until { it.preferenceError == null && fake.preferred == R }
        run.ask("again")
        assertEquals(1, r.calls.get())
    }

    @Test fun switchBackThatCannotBeSavedKeepsTheEpisodeTheOfferAndTheExhaustedMark() = runBlocking {
        val g = failing(quota()); val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val src = FailingSave(fake).also { it.failing = false }
        val run = runner(src)
        run.idle(); run.ask("one")
        assertEquals(R, fake.preferred)
        now += 4_000_000
        run.checkRecovery(); run.until { it.recoveryOffer == G.toId() }
        src.failing = true
        run.acceptRecovery()
        val s = run.until { it.preferenceError != null }
        assertEquals("still on the temporary provider", R.toId(), s.temporaryProvider)
        assertEquals(G.toId(), s.preferredProvider)
        assertEquals(R, fake.preferred)
        assertNotNull("the exhausted record must survive a failed switch-back", ledger.record(G))
        run.ask("two")
        assertNotNull("and still be there after another message", ledger.record(G))
        assertEquals("the message goes to the saved preference (Groq)", 2, r.calls.get())
        // Retrying when saving works completes the switch back.
        src.failing = false
        run.acceptRecovery()
        run.until { it.temporaryProvider == null && it.preferenceError == null }
        assertEquals(G, fake.preferred)
        assertNull("cleared only after the switch back really happened", ledger.record(G))
    }

    @Test fun aSuccessfulFailoverClearsAStaleSaveError() = runBlocking {
        val g = failing(quota()); val r = ok("from groq"); val c = ok("from cloudflare")
        val fake = FakeSource(mapOf(G to g, R to r, C to c), preferred = C).also { it.ledger = ledger }
        val src = FailingSave(fake)
        val run = runner(src)
        run.idle()
        run.choosePreferred(G.toId())                           // fails: an error is now showing
        run.until { it.preferenceError != null }
        src.failing = false
        fake.preferred = G                               // the user picks Gemini some other way, so a failover can start from it
        run.ask("hello")                                 // Gemini is exhausted -> switches (and saves) successfully
        val s = run.until { it.temporaryProvider != null && !it.generating }
        assertNull("the old error must not stay on screen after a successful switch", s.preferenceError)
        assertEquals(s.temporaryProvider, fake.preferred.toId())
    }

    // ------------------------------------------------------------------ C2d: providers that are not built-in fail closed

    private val custom = ProviderId.newCustom()

    /** Offers a custom provider as the first failover candidate. It can't run: resolving it is refused, as the real source does. */
    private class WithCustomCandidate(val inner: FakeSource, val custom: ProviderId, val acceptSave: Boolean) : ProviderSource by inner {
        val resolved = CopyOnWriteArrayList<ProviderId?>()
        val saveAttempts = CopyOnWriteArrayList<ProviderId>()
        override suspend fun snapshot(): ProviderSnapshot {
            val s = inner.snapshot()
            return s.copy(candidates = listOf(Candidate(custom, "m", true, true, true, false)) + s.candidates)
        }
        override suspend fun resolve(provider: ProviderId?): Resolved {
            resolved += provider
            if (provider == custom) return Resolved.Stop(ChatProblem("That provider can't be used for chat yet.", null, true))
            return inner.resolve(provider)
        }
        override suspend fun setPreferred(provider: ProviderId) {
            saveAttempts += provider
            if (provider == custom && acceptSave) return // pretends the save worked (the real source never does)
            inner.setPreferred(provider)               // throws for a provider that is not built-in
        }
    }

    @Test fun aFailoverTargetThatCannotBeSavedAsPreferredIsNeverSwitchedToAndNothingIsSentThroughAnyOtherProvider() = runBlocking {
        val g = failing(quota()); val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val src = WithCustomCandidate(fake, custom, acceptSave = false)
        val run = runner(src)
        run.idle(); val s = run.ask("hello")
        assertEquals(listOf(custom), src.saveAttempts.toList())
        assertEquals(1, g.calls.get()); assertEquals("no other provider may be used instead", 0, r.calls.get())
        assertNull(s.temporaryProvider); assertNull(s.preferredProvider)
        assertEquals("the saved preference is unchanged", G, fake.preferred)
        assertTrue(fake.savedPreferred.isEmpty())
        assertTrue(s.problem!!.message, s.problem!!.message.contains("did not switch"))
    }

    @Test fun aFailoverTargetThatCannotRunStopsWithAMessageAndNeverLoopsOrFallsBack() = runBlocking {
        val g = failing(quota()); val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val src = WithCustomCandidate(fake, custom, acceptSave = true)
        val run = runner(src)
        run.idle(); val s = run.ask("hello")
        assertEquals("asked once, not retried in a loop", 1, src.resolved.count { it == custom })
        assertEquals(1, g.calls.get()); assertEquals(0, r.calls.get())
        assertTrue(s.problem!!.message, s.problem!!.message.contains("can't be used for chat yet"))
        assertEquals("the user's message is kept", listOf("hello"), s.conversation.messages.map { it.text })
    }

    @Test fun choosingAProviderThatIsNotBuiltInChangesNothingAndSaysSo() = runBlocking {
        val g = ok("from gemini")
        val fake = FakeSource(mapOf(G to g), preferred = G).also { it.ledger = ledger }
        val run = runner(fake)
        run.idle()
        run.choosePreferred(custom)
        val s = run.until { it.preferenceError != null }
        assertTrue(s.preferenceError!!, s.preferenceError!!.contains("so nothing was changed"))
        assertTrue(s.preferenceError!!, s.preferenceError!!.contains("Unknown provider"))
        assertTrue(fake.savedPreferred.isEmpty())
        assertEquals(G, fake.preferred)
        val after = run.ask("hello")
        assertEquals("chat still goes to the saved provider", 1, g.calls.get())
        assertEquals("from gemini", after.conversation.messages.last().text)
    }

    @Test fun theCountdownAnnouncementNamesTheProvidersFromTheirOwnLabels() = runBlocking {
        val g = failing(quota()); val r = ok("x")
        val src = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val run = ChatRunner(storage, ChatEngine(storage), scope, Dispatchers.IO, src, ledger, FakeNetwork(), clock = { now }, countdownSeconds = 50, tickMs = 20)
        run.idle(); run.send("hi")
        val offer = run.until { it.failover != null }.failover!!
        assertEquals(G.toId(), offer.from); assertEquals(R.toId(), offer.to)
        assertEquals(G.label, offer.fromLabel); assertEquals(R.label, offer.toLabel)
        assertTrue(offer.announcement.contains("Switching to ${R.label}"))
        assertTrue(offer.disclosure.contains("will be sent to ${R.label}"))
        run.cancelFailover(); run.idle(); Unit
    }

    /** A provider that is not built-in and, in this test only, can run. Used to prove the quota ledger bridge never maps it onto a built-in entry. */
    private class CustomRunnable(val inner: FakeSource, val custom: ProviderId, val llm: LlmProvider) : ProviderSource by inner {
        override suspend fun snapshot() = inner.snapshot().copy(preferred = custom)
        override suspend fun resolve(provider: ProviderId?): Resolved {
            val id = provider ?: custom
            if (id == custom) return Resolved.Ready(ChatTarget(custom, "Custom", 10_000, "m", "KEY", "system"), llm)
            return inner.resolve(provider)
        }
    }

    @Test fun aQuotaErrorFromAProviderThatIsNotBuiltInNeverTouchesAnyBuiltInProvidersLedgerEntry() = runBlocking {
        val bad = failing(quota()); val g = ok("from gemini"); val r = ok("from groq")
        val fake = FakeSource(mapOf(G to g, R to r), preferred = G).also { it.ledger = ledger }
        val run = runner(CustomRunnable(fake, custom, bad))
        run.idle(); val s = run.ask("hello")
        assertEquals(1, bad.calls.get())
        assertTrue("no built-in provider may be recorded as used up: ${ledger.records.value}", ledger.records.value.isEmpty())
        assertEquals("the failover went to the first ready built-in provider", 1, g.calls.get())
        assertEquals(0, r.calls.get())
        assertEquals("from gemini", s.conversation.messages.last().text)
    }

    @Test fun providersThatAllRunOutOfQuotaAreEachTriedOnceEvenWithoutAnyLedgerMemoryAndNeverLoop() = runBlocking {
        val g = failing(quota()); val r = failing(quota())
        val src = FakeSource(mapOf(G to g, R to r), preferred = G) // deliberately NOT linked to the ledger: only the per-message "already tried" set can stop a loop
        val run = runner(src)
        run.idle(); val s = run.ask("hello")
        assertEquals(1, g.calls.get()); assertEquals(1, r.calls.get())
        assertTrue(s.problem!!.message, s.problem!!.message.contains("No other provider is ready"))
    }

    @Test fun aSuccessfulAnswerClearsWhatWasRememberedAboutThatProvidersQuota() = runBlocking {
        ledger.markExhausted(G, LlmError.QuotaExhausted(Instant.ofEpochMilli(now - 1_000), "PerDay", ResetSource.REPORTED))
        assertNotNull(ledger.record(G))
        val g = ok("fine")
        val run = runner(FakeSource(mapOf(G to g), preferred = G).also { it.ledger = ledger })
        run.idle(); run.ask("hello")
        assertEquals(1, g.calls.get())
        assertNull("the old record is out of date once the provider answered", ledger.record(G))
    }
}
