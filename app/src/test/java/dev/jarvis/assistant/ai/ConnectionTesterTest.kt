package dev.jarvis.assistant.ai

import dev.jarvis.assistant.chat.ChatProblem
import dev.jarvis.assistant.chat.ChatTarget
import dev.jarvis.assistant.chat.ProviderSnapshot
import dev.jarvis.assistant.chat.ProviderSource
import dev.jarvis.assistant.chat.Resolved
import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
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

        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow {
            streamRequests += request
            streamFailure?.let { throw it }
            emit("hello from ${provider.label}")
        }

        override suspend fun checkModel(apiKey: String, model: String): ModelInfo {
            checks += apiKey to model
            gate?.await()
            return ModelInfo(model, "name-of-$model", if (canGenerate) listOf("chat.completions") else emptyList())
        }

        override suspend fun generateOnce(request: ChatRequest): String { once.incrementAndGet(); return "once" }
    }

    /** Hands out targets built from per-provider settings. [preferred] is only here to prove it is never consulted. */
    private class Source(val adapters: Map<AiProvider, Adapter>) : ProviderSource {
        @Volatile var preferred: AiProvider = AiProvider.GEMINI
        val resolved = CopyOnWriteArrayList<AiProvider?>()
        var withoutKey: Set<AiProvider> = emptySet()
        var models: MutableMap<AiProvider, String> = AiProvider.entries.associateWith { "model-${it.name}" }.toMutableMap()
        var lie: AiProvider? = null // if set, answers for this provider whatever was asked (a buggy resolver)

        override suspend fun resolve(provider: AiProvider?): Resolved {
            resolved += provider
            val p = provider ?: preferred
            if (p in withoutKey) return Resolved.Stop(ChatProblem("${p.label} is not configured", null, true))
            val answerFor = lie ?: p
            return Resolved.Ready(
                ChatTarget(answerFor, 1000, models.getValue(answerFor), "KEY-${answerFor.name}", "system-for-${answerFor.name}"),
                adapters.getValue(answerFor),
            )
        }

        override suspend fun snapshot() = ProviderSnapshot(preferred, true, emptyList())
    }

    private val adapters = AiProvider.entries.associateWith { Adapter(it) }
    private val source = Source(adapters)
    private var entries: List<DiagnosticEntry> = emptyList()
    private var now = 10_000_000L

    private fun tester() = ConnectionTester(
        scope, source, { adapters.getValue(it) }, { entries }, clock = { now },
    )

    private suspend fun ConnectionTester.settled() = withTimeout(5000) { active.first { it == null } }

    private fun entry(provider: AiProvider, at: Long) = DiagnosticEntry(
        at, CallKind.GENERATE_STREAM, provider.label, "m", "POST", "e", 200, null, null, 5, 1, true, 10,
    )

    // ------------------------------------------------------------------ C-01: the provider asked for is the provider used

    @Test fun availabilityChecksTheScreenProviderNotThePreferredOne() = runBlocking {
        source.preferred = C
        val t = tester()
        assertTrue(t.checkAvailability(Q)); t.settled()
        assertEquals(listOf<AiProvider?>(Q), source.resolved)
        assertEquals(listOf("KEY-GROQ" to "model-GROQ"), adapters.getValue(Q).checks)
        assertTrue(adapters.getValue(C).checks.isEmpty())
        assertTrue(adapters.getValue(G).checks.isEmpty())
        val ok = t.stateOf(Q).availability as AvailabilityState.Ok
        assertEquals("Groq", ok.provider)
        assertEquals("model-GROQ", ok.model)
    }

    @Test fun generationTestUsesTheScreenProvidersAdapterKeyModelAndPrompt() = runBlocking {
        source.preferred = C
        val t = tester()
        assertTrue(t.runTest(G, "say hi")); t.settled()
        val sent = adapters.getValue(G).streamRequests.single()
        assertEquals("KEY-GEMINI", sent.apiKey)
        assertEquals("model-GEMINI", sent.model)
        assertEquals("system-for-GEMINI", sent.systemPrompt)
        assertEquals("say hi", sent.messages.single().text)
        assertTrue(adapters.getValue(C).streamRequests.isEmpty())
        assertTrue(adapters.getValue(Q).streamRequests.isEmpty())
        val done = t.stateOf(G).generation as TestState.Done
        assertEquals("hello from Google Gemini", done.text)
        assertEquals("Google Gemini", done.provider)
        assertEquals("model-GEMINI", done.model)
    }

    @Test fun diagnosticsUseTheScreenProvider() = runBlocking {
        source.preferred = C
        val t = tester()
        assertTrue(t.runDiagnostics(Q)); t.settled()
        assertEquals(listOf("KEY-GROQ" to "model-GROQ"), adapters.getValue(Q).checks)
        assertEquals(1, adapters.getValue(Q).streamRequests.size)
        assertTrue(adapters.getValue(C).checks.isEmpty() && adapters.getValue(C).streamRequests.isEmpty())
        val done = t.stateOf(Q).diagnostics as DiagState.Done
        assertTrue("verdict names the tested provider: ${done.verdict}", done.verdict.contains("Groq"))
        assertFalse(done.verdict.contains("Cloudflare"))
    }

    @Test fun serverSideStreamFailureMakesTheComparisonCallOnTheSameProviderOnly() = runBlocking {
        source.preferred = C
        adapters.getValue(Q).streamFailure = LlmException(LlmError.Overloaded("busy"))
        val t = tester()
        t.runDiagnostics(Q); t.settled()
        assertEquals(1, adapters.getValue(Q).once.get())
        assertEquals(0, adapters.getValue(C).once.get())
    }

    @Test fun aProviderWithoutAKeyFailsEvenIfThePreferredOneHasOne() = runBlocking {
        source.preferred = C
        source.withoutKey = setOf(Q)
        val t = tester()
        t.checkAvailability(Q); t.settled()
        t.runTest(Q, "x"); t.settled()
        t.runDiagnostics(Q); t.settled()
        assertTrue((t.stateOf(Q).availability as AvailabilityState.Failed).message.contains("Groq is not configured"))
        assertTrue((t.stateOf(Q).generation as TestState.Failed).message.contains("Groq is not configured"))
        assertTrue((t.stateOf(Q).diagnostics as DiagState.Failed).message.contains("Groq is not configured"))
        // nothing at all was sent to any adapter, in particular not to the preferred provider
        assertTrue(adapters.values.all { it.checks.isEmpty() && it.streamRequests.isEmpty() })
    }

    @Test fun aResolverThatAnswersForAnotherProviderIsRefusedAndNothingIsSent() = runBlocking {
        source.lie = C
        val t = tester()
        t.runTest(Q, "x"); t.settled()
        val failed = t.stateOf(Q).generation as TestState.Failed
        assertTrue(failed.message, failed.message.contains("Internal error"))
        assertTrue(adapters.values.all { it.streamRequests.isEmpty() })
    }

    @Test fun cooldownOfOneProviderDoesNotBlockAnother() = runBlocking {
        entries = listOf(entry(Q, now - 5_000))
        val t = tester()
        t.runTest(Q, "x"); t.settled()
        assertTrue((t.stateOf(Q).generation as TestState.Failed).message.startsWith("Wait "))
        assertTrue(adapters.getValue(Q).streamRequests.isEmpty())
        t.runTest(C, "x"); t.settled()
        assertTrue(t.stateOf(C).generation is TestState.Done)
    }

    @Test fun overloadedGeminiOffersTheAlternativeButOtherProvidersDoNot() = runBlocking {
        adapters.getValue(G).streamFailure = LlmException(LlmError.Overloaded("busy"))
        adapters.getValue(Q).streamFailure = LlmException(LlmError.Overloaded("busy"))
        // maxRetries is counted by the adapters themselves; here the fake never retries, so use maxRetries = 0
        val t = ConnectionTester(scope, source, { adapters.getValue(it) }, { entries }, clock = { now }, maxRetries = 0)
        t.runTest(G, "x"); t.settled()
        t.runTest(Q, "x"); t.settled()
        assertTrue((t.stateOf(G).generation as TestState.Failed).offerAlternative)
        assertFalse((t.stateOf(Q).generation as TestState.Failed).offerAlternative)
    }

    // ------------------------------------------------------------------ C-02: results stay with their provider

    @Test fun resultsAreKeptPerProviderAndNeverShownForAnother() = runBlocking {
        val t = tester()
        t.checkAvailability(Q); t.settled()
        assertTrue(t.stateOf(Q).availability is AvailabilityState.Ok)
        assertEquals(ProviderTestState(), t.stateOf(G))
        assertEquals(ProviderTestState(), t.stateOf(C))
        t.runTest(G, "x"); t.settled()
        assertTrue(t.stateOf(G).generation is TestState.Done)
        assertEquals(TestState.Idle, t.stateOf(Q).generation)   // Groq's generation slot is untouched by Gemini's test
        assertTrue(t.stateOf(Q).availability is AvailabilityState.Ok) // and Groq's earlier result is still there
    }

    // ------------------------------------------------------------------ D1: one check at a time

    @Test fun onlyOneCheckRunsAtATimeAndTheRefusedOneSendsNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        adapters.getValue(Q).gate = gate
        val t = tester()
        assertTrue(t.checkAvailability(Q))
        withTimeout(5000) { while (adapters.getValue(Q).checks.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(ActiveTest(Q, TestKind.AVAILABILITY), t.active.value)
        assertTrue(t.stateOf(Q).availability is AvailabilityState.Running)

        assertFalse("another provider", t.runTest(G, "x"))
        assertFalse("same provider, other check", t.runDiagnostics(Q))
        assertFalse("same check again", t.checkAvailability(Q))
        assertEquals("refused calls must not even resolve a provider", listOf<AiProvider?>(Q), source.resolved)
        assertEquals(ProviderTestState(), t.stateOf(G))

        gate.complete(Unit); t.settled()
        assertTrue(t.stateOf(Q).availability is AvailabilityState.Ok)
        assertTrue("free again", t.runTest(G, "x")); t.settled()
        assertTrue(t.stateOf(G).generation is TestState.Done)
    }

    // ------------------------------------------------------------------ D2: a changed key or model makes results stale

    @Test fun changedKeyDropsThatProvidersResultsOnly() = runBlocking {
        val t = tester()
        t.checkAvailability(Q); t.settled(); t.checkAvailability(G); t.settled()
        t.invalidate(Q)
        assertEquals(ProviderTestState(), t.stateOf(Q))
        assertTrue(t.stateOf(G).availability is AvailabilityState.Ok)
    }

    @Test fun invalidateWhileRunningStopsTheCheckAndNoLateResultAppears() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        adapters.getValue(Q).gate = gate
        val t = tester()
        t.checkAvailability(Q)
        withTimeout(5000) { while (adapters.getValue(Q).checks.isEmpty()) kotlinx.coroutines.delay(5) }
        t.invalidate(Q)
        t.settled()
        gate.complete(Unit)
        kotlinx.coroutines.delay(100)
        assertEquals(ProviderTestState(), t.stateOf(Q))
        assertNull(t.active.value)
        // and a fresh check afterwards works and is tied to the new configuration
        adapters.getValue(Q).gate = null
        assertTrue(t.checkAvailability(Q)); t.settled()
        assertTrue(t.stateOf(Q).availability is AvailabilityState.Ok)
    }

    @Test fun changedModelDropsOnlyTheProviderWhoseModelChanged() = runBlocking {
        val t = tester()
        val before = AiProvider.entries.associateWith { "model-${it.name}" }
        t.onModelsObserved(before)                       // first look: nothing to compare with, nothing dropped
        t.checkAvailability(Q); t.settled(); t.checkAvailability(G); t.settled()
        t.onModelsObserved(before)                       // unchanged: results stay
        assertTrue(t.stateOf(Q).availability is AvailabilityState.Ok)
        t.onModelsObserved(before + (Q to "another-model"))
        assertEquals(ProviderTestState(), t.stateOf(Q))
        assertTrue(t.stateOf(G).availability is AvailabilityState.Ok)
    }

    @Test fun afterAModelChangeTheNextCheckUsesTheNewModel() = runBlocking {
        val t = tester()
        t.checkAvailability(Q); t.settled()
        source.models[Q] = "new-model"
        t.invalidate(Q)
        t.checkAvailability(Q); t.settled()
        assertEquals("new-model", (t.stateOf(Q).availability as AvailabilityState.Ok).model)
        assertEquals(listOf("model-GROQ", "new-model"), adapters.getValue(Q).checks.map { it.second })
    }
}
