package dev.jarvis.assistant.ai

import dev.jarvis.assistant.chat.ChatProblem
import dev.jarvis.assistant.chat.chatTarget
import dev.jarvis.assistant.chat.ProviderSnapshot
import dev.jarvis.assistant.chat.ProviderSource
import dev.jarvis.assistant.chat.Resolved
import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.chat.builtInLabelOf
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.toAiProviderOrNull
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The connection tests must act on the provider they are told to test (C-01), keep their results per provider (C-02),
 * run one at a time (D1) and drop results when the key or model changes (D2). Every test here uses a PREFERRED chat
 * provider that differs from the provider under test, which is exactly the situation that used to go wrong.
 */
class ConnectionTesterTest {
    private val G = AiProvider.GEMINI
    private val Q = AiProvider.GROQ
    private val C = AiProvider.CLOUDFLARE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    /** One fake adapter per provider; it records everything it was asked to do. */
    private class Adapter(val provider: AiProvider) : LlmProvider, ProviderDiagnostics {
        val streamRequests = CopyOnWriteArrayList<ChatRequest>()
        val checks = CopyOnWriteArrayList<Pair<String, String>>() // apiKey to model
        val once = AtomicInteger(0)
        @Volatile var gate: CompletableDeferred<Unit>? = null     // when set, checkModel waits for it
        @Volatile var streamFailure: LlmException? = null
        @Volatile var canGenerate = true
        @Volatile var listFailure: LlmException? = null           // e.g. a server with no model list
        @Volatile var ignoresCancellation = false                 // models work that keeps running after the check is stopped

        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow {
            streamRequests += request
            streamFailure?.let { throw it }
            emit("hello from ${provider.label}")
        }

        override suspend fun checkModel(apiKey: String, model: String): ModelInfo {
            checks += apiKey to model
            listFailure?.let { throw it }
            if (ignoresCancellation) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate?.await() } else gate?.await()
            return ModelInfo(model, "name-of-$model", if (canGenerate) listOf("chat.completions") else emptyList())
        }

        override suspend fun generateOnce(request: ChatRequest): String { once.incrementAndGet(); return "once" }
    }

    /** Hands out targets built from per-provider settings. [preferred] is only here to prove it is never consulted. */
    private class Source(val adapters: Map<AiProvider, Adapter>) : ProviderSource {
        @Volatile var preferred: AiProvider = AiProvider.GEMINI
        val resolved = CopyOnWriteArrayList<ProviderId?>()
        var withoutKey: Set<AiProvider> = emptySet()
        var models: MutableMap<AiProvider, String> = AiProvider.entries.associateWith { "model-${it.name}" }.toMutableMap()
        var lie: AiProvider? = null // if set, answers for this provider whatever was asked (a buggy resolver)

        override fun labelOf(id: ProviderId) = builtInLabelOf(id)

        override suspend fun resolve(provider: ProviderId?): Resolved {
            resolved += provider
            val p = provider?.toAiProviderOrNull() ?: preferred
            if (p in withoutKey) return Resolved.Stop(ChatProblem("${p.label} is not configured", null, true))
            val answerFor = lie ?: p
            return Resolved.Ready(
                chatTarget(answerFor, 1000, models.getValue(answerFor), "KEY-${answerFor.name}", "system-for-${answerFor.name}"),
                adapters.getValue(answerFor),
            )
        }

        override suspend fun snapshot() = ProviderSnapshot(preferred.toId(), true, emptyList())
    }

    private val adapters = AiProvider.entries.associateWith { Adapter(it) }
    private val source = Source(adapters)
    private var entries: List<DiagnosticEntry> = emptyList()
    private var now = 10_000_000L

    private fun tester() = ConnectionTester(
        scope, source, ::labelOf, { id -> id.toAiProviderOrNull()?.let { adapters.getValue(it) } }, { entries }, clock = { now },
    )

    private val registry = ProviderRegistry.withBuiltIns()
    private fun labelOf(id: dev.jarvis.assistant.providers.ProviderId) = registry.get(id)?.label ?: id.value

    private suspend fun ConnectionTester.settled() = withTimeout(5000) { active.first { it == null } }

    private fun entry(provider: AiProvider, at: Long) = DiagnosticEntry(
        at, CallKind.GENERATE_STREAM, provider.label, "m", "POST", "e", 200, null, null, 5, 1, true, 10,
    )

    // ------------------------------------------------------------------ C-01: the provider asked for is the provider used

    @Test fun availabilityChecksTheScreenProviderNotThePreferredOne() = runBlocking {
        source.preferred = C
        val t = tester()
        assertTrue(t.checkAvailability(Q.toId())); t.settled()
        assertEquals(listOf<ProviderId?>(Q.toId()), source.resolved)
        assertEquals(listOf("KEY-GROQ" to "model-GROQ"), adapters.getValue(Q).checks)
        assertTrue(adapters.getValue(C).checks.isEmpty())
        assertTrue(adapters.getValue(G).checks.isEmpty())
        val ok = t.stateOf(Q.toId()).availability as AvailabilityState.Ok
        assertEquals("Groq", ok.provider)
        assertEquals("model-GROQ", ok.model)
    }

    @Test fun generationTestUsesTheScreenProvidersAdapterKeyModelAndPrompt() = runBlocking {
        source.preferred = C
        val t = tester()
        assertTrue(t.runTest(G.toId(), "say hi")); t.settled()
        val sent = adapters.getValue(G).streamRequests.single()
        assertEquals("KEY-GEMINI", sent.apiKey)
        assertEquals("model-GEMINI", sent.model)
        assertEquals("system-for-GEMINI", sent.systemPrompt)
        assertEquals("say hi", sent.messages.single().text)
        assertTrue(adapters.getValue(C).streamRequests.isEmpty())
        assertTrue(adapters.getValue(Q).streamRequests.isEmpty())
        val done = t.stateOf(G.toId()).generation as TestState.Done
        assertEquals("hello from Google Gemini", done.text)
        assertEquals("Google Gemini", done.provider)
        assertEquals("model-GEMINI", done.model)
    }

    @Test fun diagnosticsUseTheScreenProvider() = runBlocking {
        source.preferred = C
        val t = tester()
        assertTrue(t.runDiagnostics(Q.toId())); t.settled()
        assertEquals(listOf("KEY-GROQ" to "model-GROQ"), adapters.getValue(Q).checks)
        assertEquals(1, adapters.getValue(Q).streamRequests.size)
        assertTrue(adapters.getValue(C).checks.isEmpty() && adapters.getValue(C).streamRequests.isEmpty())
        val done = t.stateOf(Q.toId()).diagnostics as DiagState.Done
        assertTrue("verdict names the tested provider: ${done.verdict}", done.verdict.contains("Groq"))
        assertFalse(done.verdict.contains("Cloudflare"))
    }

    @Test fun serverSideStreamFailureMakesTheComparisonCallOnTheSameProviderOnly() = runBlocking {
        source.preferred = C
        adapters.getValue(Q).streamFailure = LlmException(LlmError.Overloaded("busy"))
        val t = tester()
        t.runDiagnostics(Q.toId()); t.settled()
        assertEquals(1, adapters.getValue(Q).once.get())
        assertEquals(0, adapters.getValue(C).once.get())
    }

    @Test fun aProviderWithoutAKeyFailsEvenIfThePreferredOneHasOne() = runBlocking {
        source.preferred = C
        source.withoutKey = setOf(Q)
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled()
        t.runTest(Q.toId(), "x"); t.settled()
        t.runDiagnostics(Q.toId()); t.settled()
        assertTrue((t.stateOf(Q.toId()).availability as AvailabilityState.Failed).message.contains("Groq is not configured"))
        assertTrue((t.stateOf(Q.toId()).generation as TestState.Failed).message.contains("Groq is not configured"))
        assertTrue((t.stateOf(Q.toId()).diagnostics as DiagState.Failed).message.contains("Groq is not configured"))
        // nothing at all was sent to any adapter, in particular not to the preferred provider
        assertTrue(adapters.values.all { it.checks.isEmpty() && it.streamRequests.isEmpty() })
    }

    @Test fun aResolverThatAnswersForAnotherProviderIsRefusedAndNothingIsSent() = runBlocking {
        source.lie = C
        val t = tester()
        t.runTest(Q.toId(), "x"); t.settled()
        val failed = t.stateOf(Q.toId()).generation as TestState.Failed
        assertTrue(failed.message, failed.message.contains("Internal error"))
        assertTrue(adapters.values.all { it.streamRequests.isEmpty() })
    }

    @Test fun cooldownOfOneProviderDoesNotBlockAnother() = runBlocking {
        entries = listOf(entry(Q, now - 5_000))
        val t = tester()
        t.runTest(Q.toId(), "x"); t.settled()
        assertTrue((t.stateOf(Q.toId()).generation as TestState.Failed).message.startsWith("Wait "))
        assertTrue(adapters.getValue(Q).streamRequests.isEmpty())
        t.runTest(C.toId(), "x"); t.settled()
        assertTrue(t.stateOf(C.toId()).generation is TestState.Done)
    }

    @Test fun overloadedGeminiOffersTheAlternativeButOtherProvidersDoNot() = runBlocking {
        adapters.getValue(G).streamFailure = LlmException(LlmError.Overloaded("busy"))
        adapters.getValue(Q).streamFailure = LlmException(LlmError.Overloaded("busy"))
        // maxRetries is counted by the adapters themselves; here the fake never retries, so use maxRetries = 0
        val t = ConnectionTester(scope, source, ::labelOf, { id -> id.toAiProviderOrNull()?.let { adapters.getValue(it) } }, { entries }, clock = { now }, maxRetries = 0)
        t.runTest(G.toId(), "x"); t.settled()
        t.runTest(Q.toId(), "x"); t.settled()
        assertTrue((t.stateOf(G.toId()).generation as TestState.Failed).offerAlternative)
        assertFalse((t.stateOf(Q.toId()).generation as TestState.Failed).offerAlternative)
    }

    // ------------------------------------------------------------------ C-02: results stay with their provider

    @Test fun resultsAreKeptPerProviderAndNeverShownForAnother() = runBlocking {
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled()
        assertTrue(t.stateOf(Q.toId()).availability is AvailabilityState.Ok)
        assertEquals(ProviderTestState(), t.stateOf(G.toId()))
        assertEquals(ProviderTestState(), t.stateOf(C.toId()))
        t.runTest(G.toId(), "x"); t.settled()
        assertTrue(t.stateOf(G.toId()).generation is TestState.Done)
        assertEquals(TestState.Idle, t.stateOf(Q.toId()).generation)   // Groq's generation slot is untouched by Gemini's test
        assertTrue(t.stateOf(Q.toId()).availability is AvailabilityState.Ok) // and Groq's earlier result is still there
    }

    // ------------------------------------------------------------------ D1: one check at a time

    @Test fun onlyOneCheckRunsAtATimeAndTheRefusedOneSendsNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        adapters.getValue(Q).gate = gate
        val t = tester()
        assertTrue(t.checkAvailability(Q.toId()))
        withTimeout(5000) { while (adapters.getValue(Q).checks.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(ActiveTest(Q.toId(), TestKind.AVAILABILITY), t.active.value)
        assertTrue(t.stateOf(Q.toId()).availability is AvailabilityState.Running)

        assertFalse("another provider", t.runTest(G.toId(), "x"))
        assertFalse("same provider, other check", t.runDiagnostics(Q.toId()))
        assertFalse("same check again", t.checkAvailability(Q.toId()))
        assertEquals("refused calls must not even resolve a provider", listOf<ProviderId?>(Q.toId()), source.resolved)
        assertEquals(ProviderTestState(), t.stateOf(G.toId()))

        gate.complete(Unit); t.settled()
        assertTrue(t.stateOf(Q.toId()).availability is AvailabilityState.Ok)
        assertTrue("free again", t.runTest(G.toId(), "x")); t.settled()
        assertTrue(t.stateOf(G.toId()).generation is TestState.Done)
    }

    // ------------------------------------------------------------------ D2: a changed key or model makes results stale

    @Test fun changedKeyDropsThatProvidersResultsOnly() = runBlocking {
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled(); t.checkAvailability(G.toId()); t.settled()
        t.invalidate(Q.toId())
        assertEquals(ProviderTestState(), t.stateOf(Q.toId()))
        assertTrue(t.stateOf(G.toId()).availability is AvailabilityState.Ok)
    }

    @Test fun invalidateWhileRunningStopsTheCheckAndNoLateResultAppears() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        adapters.getValue(Q).gate = gate
        val t = tester()
        t.checkAvailability(Q.toId())
        withTimeout(5000) { while (adapters.getValue(Q).checks.isEmpty()) kotlinx.coroutines.delay(5) }
        t.invalidate(Q.toId())
        t.settled()
        gate.complete(Unit)
        kotlinx.coroutines.delay(100)
        assertEquals(ProviderTestState(), t.stateOf(Q.toId()))
        assertNull(t.active.value)
        // and a fresh check afterwards works and is tied to the new configuration
        adapters.getValue(Q).gate = null
        assertTrue(t.checkAvailability(Q.toId())); t.settled()
        assertTrue(t.stateOf(Q.toId()).availability is AvailabilityState.Ok)
    }

    @Test fun changedModelDropsOnlyTheProviderWhoseModelChanged() = runBlocking {
        val t = tester()
        val before = AiProvider.entries.associate { it.toId() to "model-${it.name}" }
        t.onModelsObserved(before)                       // first look: nothing to compare with, nothing dropped
        t.checkAvailability(Q.toId()); t.settled(); t.checkAvailability(G.toId()); t.settled()
        t.onModelsObserved(before)                       // unchanged: results stay
        assertTrue(t.stateOf(Q.toId()).availability is AvailabilityState.Ok)
        t.onModelsObserved(before + (Q.toId() to "another-model"))
        assertEquals(ProviderTestState(), t.stateOf(Q.toId()))
        assertTrue(t.stateOf(G.toId()).availability is AvailabilityState.Ok)
    }

    @Test fun afterAModelChangeTheNextCheckUsesTheNewModel() = runBlocking {
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled()
        source.models[Q] = "new-model"
        t.invalidate(Q.toId())
        t.checkAvailability(Q.toId()); t.settled()
        assertEquals("new-model", (t.stateOf(Q.toId()).availability as AvailabilityState.Ok).model)
        assertEquals(listOf("model-GROQ", "new-model"), adapters.getValue(Q).checks.map { it.second })
    }

    // ------------------------------------------------------------------ C2c: results are keyed by ProviderId

    @Test fun anIdThatIsNotABuiltInProviderIsRefusedBeforeAnythingIsResolvedOrSent() = runBlocking {
        val stranger = dev.jarvis.assistant.providers.ProviderId.newCustom()
        val t = tester()
        t.checkAvailability(stranger); t.settled()
        t.runTest(stranger, "x"); t.settled()
        t.runDiagnostics(stranger); t.settled()
        assertTrue(source.resolved.isEmpty())
        assertTrue(adapters.values.all { it.checks.isEmpty() && it.streamRequests.isEmpty() && it.once.get() == 0 })
        assertTrue(t.stateOf(stranger).availability is AvailabilityState.Failed)
        assertTrue(t.stateOf(stranger).generation is TestState.Failed)
        assertTrue(t.stateOf(stranger).diagnostics is DiagState.Failed)
        // and no built-in provider picked up a result for it
        for (p in AiProvider.entries) assertEquals(ProviderTestState(), t.stateOf(p.toId()))
    }

    @Test fun invalidatingOneIdNeverBumpsAnotherIdsEpochOrState() = runBlocking {
        val t = tester()
        t.checkAvailability(G.toId()); t.settled()
        t.invalidate(dev.jarvis.assistant.providers.ProviderId.newCustom())
        t.invalidate(Q.toId())
        assertTrue(t.stateOf(G.toId()).availability is AvailabilityState.Ok)
    }

    @Test fun anAvailabilityCheckAloneIsAcceptedNotPassedAndOnlyAGenerationReplyPasses() = runBlocking {
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled()
        assertEquals(
            dev.jarvis.assistant.providers.TestStatus.ACCEPTED,
            dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(Q.toId())),
        )
        t.runTest(Q.toId(), "x"); t.settled()
        assertEquals(
            dev.jarvis.assistant.providers.TestStatus.PASSED,
            dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(Q.toId())),
        )
        // the other providers are still untested
        assertEquals(
            dev.jarvis.assistant.providers.TestStatus.NOT_TESTED,
            dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(G.toId())),
        )
    }

    @Test fun aKeyChangeInvalidatesPassedBackToNotTestedAndANewRunIsNeeded() = runBlocking {
        val t = tester()
        t.runTest(Q.toId(), "x"); t.settled()
        t.invalidate(Q.toId())
        assertEquals(
            dev.jarvis.assistant.providers.TestStatus.NOT_TESTED,
            dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(Q.toId())),
        )
    }

    @Test fun theActiveTestNamesTheProviderIdBeingTested() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        adapters.getValue(C).gate = gate
        val t = tester()
        t.checkAvailability(C.toId())
        withTimeout(5000) { while (adapters.getValue(C).checks.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(C.toId(), t.active.value?.provider)
        gate.complete(Unit); t.settled(); Unit
    }

    @Test fun aResultThatArrivesAfterTheKeyChangedIsDiscardedEvenWhenTheStoppedCheckKeepsRunning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        adapters.getValue(Q).gate = gate
        adapters.getValue(Q).ignoresCancellation = true
        val t = tester()
        t.checkAvailability(Q.toId())
        withTimeout(5000) { while (adapters.getValue(Q).checks.isEmpty()) kotlinx.coroutines.delay(5) }
        t.invalidate(Q.toId())          // the key changed while the check was in flight
        gate.complete(Unit)             // the old check now finishes anyway, with the OLD key's answer
        kotlinx.coroutines.delay(200)
        assertEquals("a stale result must never be shown", ProviderTestState(), t.stateOf(Q.toId()))
    }

    // ------------------------------------------------------------------ C3a: no model list is not a pass, and not a failure

    private val noList = LlmException(LlmError.ModelListUnavailable(404))

    @Test fun anUnavailableModelListIsUnverifiedAndNeverAcceptedOrPassed() = runBlocking {
        adapters.getValue(Q).listFailure = noList
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled()
        val state = t.stateOf(Q.toId())
        assertTrue(state.availability.toString(), state.availability is AvailabilityState.Unverified)
        assertEquals(dev.jarvis.assistant.providers.TestStatus.NOT_TESTED, dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(state))
        assertTrue("no generation was made by the check", adapters.getValue(Q).streamRequests.isEmpty() && adapters.getValue(Q).once.get() == 0)
    }

    @Test fun onlyARealGenerationAfterAnUnverifiedListPasses() = runBlocking {
        adapters.getValue(Q).listFailure = noList
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled()
        t.runTest(Q.toId(), "x"); t.settled()
        assertEquals(dev.jarvis.assistant.providers.TestStatus.PASSED, dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(Q.toId())))
    }

    @Test fun aFailedGenerationAfterAnUnverifiedListIsFailed() = runBlocking {
        adapters.getValue(Q).listFailure = noList
        adapters.getValue(Q).streamFailure = LlmException(LlmError.InvalidKey)
        val t = tester()
        t.checkAvailability(Q.toId()); t.settled()
        t.runTest(Q.toId(), "x"); t.settled()
        assertEquals(dev.jarvis.assistant.providers.TestStatus.FAILED, dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(Q.toId())))
    }

    @Test fun diagnosticsStillRunGenerationWhenTheListIsUnavailableAndSayWhatWasNotVerified() = runBlocking {
        adapters.getValue(Q).listFailure = noList
        val t = tester()
        assertTrue(t.runDiagnostics(Q.toId())); t.settled()
        assertEquals("generation was still attempted", 1, adapters.getValue(Q).streamRequests.size)
        val done = t.stateOf(Q.toId()).diagnostics as DiagState.Done
        val check = done.lines.first()
        assertTrue(check.unverified); assertEquals(null, check.ok)
        assertTrue(done.verdict, done.verdict.contains("model list") && done.verdict.contains("streamed reply came back"))
        assertFalse(done.verdict.startsWith("Everything works"))
        assertEquals("diagnostics alone never make a pass", dev.jarvis.assistant.providers.TestStatus.NOT_TESTED, dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(Q.toId())))
    }

    @Test fun diagnosticsWithAnUnavailableListAndAFailedGenerationReportFailureNotSuccess() = runBlocking {
        adapters.getValue(Q).listFailure = noList
        adapters.getValue(Q).streamFailure = LlmException(LlmError.InvalidKey)
        val t = tester()
        t.runDiagnostics(Q.toId()); t.settled()
        val done = t.stateOf(Q.toId()).diagnostics as DiagState.Done
        assertTrue(done.verdict, done.verdict.contains("generation failed"))
        assertEquals(dev.jarvis.assistant.providers.TestStatus.FAILED, dev.jarvis.assistant.providers.ProviderStatusModel.testStatus(t.stateOf(Q.toId())))
    }

    @Test fun aRealListFailureStillStopsDiagnosticsBeforeGeneration() = runBlocking {
        adapters.getValue(Q).listFailure = LlmException(LlmError.InvalidKey)
        val t = tester()
        t.runDiagnostics(Q.toId()); t.settled()
        assertTrue("an invalid key must not lead to generation calls", adapters.getValue(Q).streamRequests.isEmpty())
    }

    @Test fun aProviderWithNoDiagnosticsAdapterFailsBeforeAnythingIsSent() = runBlocking {
        val t = ConnectionTester(scope, source, ::labelOf, { null }, { entries }, clock = { now })
        t.runDiagnostics(Q.toId()); t.settled()
        val d = t.stateOf(Q.toId()).diagnostics
        assertTrue(d.toString(), d is DiagState.Failed && d.message.contains("Nothing was sent"))
        assertTrue(adapters.getValue(Q).streamRequests.isEmpty() && adapters.getValue(Q).checks.isEmpty())
    }
}
