package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicInteger

class ChatRunnerFailoverTest {
    private val G = AiProvider.GEMINI
    private val R = AiProvider.GROQ
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = FileChatStorage(Files.createTempDirectory("fo").toFile())
    private var now = 1_000_000L
    private val ledger = newLedger { now }
    private val net = FakeNetwork()

    @After fun tearDown() = scope.cancel()

    private open class Fake(val block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) : LlmProvider {
        val calls = AtomicInteger(0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<ChatRequest>()
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> {
            val n = calls.incrementAndGet()
            requests += request
            return flow { block(n) }
        }
    }

    private fun fail(e: LlmError) = LlmException(e)
    private fun quota() = fail(LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 3_600_000), "PerDay", ResetSource.REPORTED))
    private fun ok(text: String) = Fake { emit(text) }
    private fun throwing(e: LlmException) = Fake { throw e }

    private val traces = java.util.concurrent.CopyOnWriteArrayList<TraceEvent>()

    private fun runner(source: FakeSource, countdown: Int = 2) =
        ChatRunner(
            storage, ChatEngine(storage), scope, Dispatchers.IO, source.also { it.ledger = ledger }, ledger, net,
            clock = { now }, countdownSeconds = countdown, tickMs = 20,
            trace = object : TraceSink { override fun record(event: TraceEvent) { traces += event } },
        )

    private class GeminiFake(block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) : Fake(block)
    private class GroqFake(block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) : Fake(block)


    private suspend fun ChatRunner.idle() = withTimeout(5000) { state.first { it.loaded && !it.generating } }
    private suspend fun ChatRunner.sendWhenFree(t: String) = withTimeout(5000) { while (send(t) != SendResult.ACCEPTED) delay(5) }
    private suspend fun ChatRunner.until(c: (ChatUiState) -> Boolean) = withTimeout(5000) { state.first(c) }

    @Test fun quotaThenCountdownThenSwitchesOnceWithoutDuplicates() = runBlocking {
        val a = throwing(quota()); val b = ok("from groq")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle()
        assertEquals(SendResult.ACCEPTED, r.send("hello"))
        val offer = r.until { it.failover != null }.failover!!
        assertEquals("Your current AI provider has exhausted its available quota. Switching to ${R.label} in 2 seconds.", offer.announcement)
        assertTrue(offer.disclosure.contains("conversation so far will be sent to ${R.label}"))
        val s = r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(1, b.calls.get())
        assertEquals(listOf("hello", "from groq"), s.conversation.messages.map { it.text })
        assertEquals(R, s.temporaryProvider)
        assertEquals(G, s.preferredProvider)
        assertTrue(ledger.isExhausted(G))
        assertNull(s.problem)
        assertEquals("hello", b.requests.single().messages.last().text)
    }

    @Test fun cancelDuringCountdownStaysAndKeepsMessage() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 50)
        r.idle(); r.send("hi")
        r.until { it.failover != null }
        r.cancelFailover()
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertEquals(listOf("hi"), s.conversation.messages.map { it.text })
        assertNull(s.temporaryProvider)
        assertTrue(s.problem!!.message.startsWith("Switch cancelled"))
    }

    @Test fun stopDuringCountdownAlsoCancelsTheSwitch() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 50)
        r.idle(); r.send("hi")
        r.until { it.failover != null }
        r.stop()
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertNull(s.temporaryProvider)
        assertTrue(s.problem!!.message.startsWith("Switch cancelled"))
    }

    @Test fun failoverDisabledShowsErrorAndNeverCallsOther() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G, failoverEnabled = false))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertTrue(s.problem!!.message.contains("failover is off"))
        assertEquals(listOf("hi"), s.conversation.messages.map { it.text })
    }

    @Test fun partialTextThenQuotaErrorDoesNotSwitch() = runBlocking {
        val a = Fake { emit("half an answ"); throw quota() }; val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertNull(s.temporaryProvider)
        assertNotNull(s.problem)
        assertNull(s.failover)
    }

    @Test fun noAlternativeListsReasons() = runBlocking {
        val a = throwing(quota())
        val r = runner(FakeSource(mapOf(G to a, R to ok("x")), preferred = G, withoutKey = setOf(R)))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertTrue(s.problem!!.message.contains("No other provider is ready"))
        assertTrue(s.problem!!.message.contains("no API key"))
        assertTrue(s.problem!!.needsSetup)
    }

    @Test fun shortRateLimitWaitsAndRetriesOnceOnSameProvider() = runBlocking {
        val a = Fake { n -> if (n == 1) throw fail(LlmError.RateLimited(1)) else emit("later") }
        val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(2, a.calls.get())
        assertEquals(0, b.calls.get())
        assertEquals(listOf("hi", "later"), s.conversation.messages.map { it.text })
        assertNull(s.temporaryProvider)
    }

    @Test fun rateLimitThatPersistsShowsErrorAfterOneRetry() = runBlocking {
        val a = throwing(fail(LlmError.RateLimited(1))); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(2, a.calls.get())
        assertEquals(0, b.calls.get())
        assertNotNull(s.problem)
    }

    @Test fun longRateLimitDoesNotWaitOrSwitch() = runBlocking {
        val a = throwing(fail(LlmError.RateLimited(120))); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(0, b.calls.get())
    }

    @Test fun offlineWaitsForNetworkThenRetriesOnce() = runBlocking {
        val a = Fake { n -> if (n == 1) throw fail(LlmError.Offline) else emit("back") }
        val r = runner(FakeSource(mapOf(G to a, R to ok("x")), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(1, net.waits)
        assertEquals(2, a.calls.get())
        assertEquals(listOf("hi", "back"), s.conversation.messages.map { it.text })
    }

    @Test fun otherErrorsNeverSwitch() = runBlocking {
        for (e in listOf(LlmError.Overloaded(null), LlmError.InvalidKey)) {
            val a = throwing(fail(e)); val b = ok("x")
            val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
            r.idle(); r.send("hi")
            r.idle()
            assertEquals(0, b.calls.get())
        }
    }

    @Test fun knownExhaustedProviderSkipsStraightToCountdownWithoutCallingIt() = runBlocking {
        ledger.markExhausted(G, LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 3_600_000), "d", ResetSource.REPORTED))
        val a = ok("should not be called"); val b = ok("from groq")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(0, a.calls.get())
        assertEquals(1, b.calls.get())
        assertEquals(R, s.temporaryProvider)
    }

    @Test fun nextMessageStaysOnTemporaryProvider() = runBlocking {
        val a = throwing(quota()); val b = ok("answer")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("one"); r.idle()
        r.sendWhenFree("two")
        val s = r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(2, b.calls.get())
        assertEquals(4, s.conversation.messages.size)
    }

    @Test fun recoveryIsOfferedNotAutomaticAcceptAndDecline() = runBlocking {
        val a = Fake { n -> if (n == 1) throw quota() else emit("gemini again") }; val b = ok("answer")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("one"); r.idle()
        assertNull(r.state.value.recoveryOffer) // still exhausted
        now += 3_700_000 // the reported reset passes
        r.checkRecovery()
        r.until { it.recoveryOffer == G }
        assertEquals(R, r.state.value.temporaryProvider) // not switched silently
        r.declineRecovery()
        assertNull(r.state.value.recoveryOffer)
        r.checkRecovery()
        delay(100)
        assertNull(r.state.value.recoveryOffer) // doesn't nag
        assertEquals(R, r.state.value.temporaryProvider)
        // Sending counts as staying; accepting later still works.
        r.acceptRecovery()
        assertNull(r.state.value.temporaryProvider)
        r.sendWhenFree("two")
        val s = r.idle()
        assertEquals("gemini again", s.conversation.messages.last().text)
    }

    @Test fun countdownPausesWhileAppIsOffScreen() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 1)
        r.idle()
        r.setVisible(false)
        r.send("hi")
        r.until { it.failover != null }
        delay(300)
        assertEquals(0, b.calls.get()) // not switched while hidden
        assertTrue(r.state.value.failover != null)
        r.setVisible(true)
        r.idle()
        assertEquals(1, b.calls.get())
    }

    @Test fun simulationSwitchesWithoutCallingAnyRealProvider() = runBlocking {
        val a = ok("real gemini"); val b = ok("from groq")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle()
        r.armSimulation()
        assertTrue(r.state.value.simulationArmed)
        r.send("hi")
        val s = r.idle()
        assertEquals(0, a.calls.get())
        assertEquals(1, b.calls.get())
        assertFalse(s.simulationArmed)
        assertEquals(R, s.temporaryProvider)
    }

    @Test fun duplicateSendDuringCountdownIsRefused() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 50)
        r.idle(); r.send("hi")
        r.until { it.failover != null }
        assertEquals(SendResult.BUSY, r.send("hi again"))
        r.cancelFailover()
        val s = r.idle()
        assertEquals(listOf("hi"), s.conversation.messages.map { it.text })
    }

    // ------------------------------------------------------------------ both directions, metadata, adapter identity


    @Test fun geminiQuotaToGroqUsesGroqAdapterModelAndKeyAndLabelsTheReply() = runBlocking {
        val a = GeminiFake { throw quota() }
        val b = GroqFake { emit("groq answer") }
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hello")
        val s = r.idle()
        val reply = s.conversation.messages.last()
        assertEquals(R.label, reply.provider)
        assertEquals("model-${R.name}", reply.model)
        assertEquals(1, a.calls.get())
        assertEquals(1, b.calls.get())
        assertEquals("model-${R.name}", b.requests.single().model) // Groq's own model, not Gemini's
        assertEquals("KEY", b.requests.single().apiKey)
        val d = s.dispatch!!
        assertEquals(G, d.requested)
        assertEquals(R, d.actual)
        assertEquals("GroqFake", d.adapter)
        assertEquals(DispatchStage.FINISHED_OK, d.stage)
        assertFalse(d.mismatch)
        assertEquals("${G.label} \u2192 GroqFake \u2192 model-${R.name}", d.line)
    }

    @Test fun groqQuotaToGeminiWorksTheOtherWayRound() = runBlocking {
        val a = GeminiFake { emit("gemini answer") }
        val b = GroqFake { throw quota() }
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = R))
        r.idle(); r.send("hello")
        val s = r.idle()
        val reply = s.conversation.messages.last()
        assertEquals(G.label, reply.provider)
        assertEquals("model-${G.name}", reply.model)
        assertEquals(1, b.calls.get())
        assertEquals(1, a.calls.get())
        assertEquals("model-${G.name}", a.requests.single().model)
        assertEquals(listOf("hello", "gemini answer"), s.conversation.messages.map { it.text })
        assertEquals(G, s.temporaryProvider)
        assertEquals(R, s.preferredProvider)
        assertEquals("GeminiFake", s.dispatch!!.adapter)
        assertEquals(R, s.dispatch!!.requested)
    }

    @Test fun exhaustedAdapterIsNeverReusedForTheRetry() = runBlocking {
        val a = GeminiFake { throw quota() }
        val b = GroqFake { emit("ok") }
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("one")
        r.idle()
        r.sendWhenFree("two")
        r.idle()
        assertEquals(1, a.calls.get()) // Gemini was asked exactly once in total
        assertEquals(2, b.calls.get())
    }

    @Test fun fallbackThatAlsoFailsShowsItsErrorAndDoesNotBounceBack() = runBlocking {
        val a = GeminiFake { throw quota() }
        val b = GroqFake { throw fail(LlmError.Overloaded(null)) }
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(1, b.calls.get())
        assertNotNull(s.problem)
        assertEquals(listOf("hello"), s.conversation.messages.map { it.text }) // no duplicate user message
        assertEquals(DispatchStage.FINISHED_FAILED, s.dispatch!!.stage)
        assertEquals("GroqFake", s.dispatch!!.adapter)
    }

    @Test fun bothProvidersOutOfQuotaStopsWithAnExplanation() = runBlocking {
        val a = GeminiFake { throw quota() }
        val b = GroqFake { throw quota() }
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(1, b.calls.get())
        assertTrue(s.problem!!.message.contains("No other provider is ready"))
        assertEquals(listOf("hello"), s.conversation.messages.map { it.text })
    }

    // ------------------------------------------------------------------ simulation follows the real path

    @Test fun simulatedQuotaTakesTheSamePathAsRealQuotaAndTheRetryIsReal() = runBlocking {
        val realA = GeminiFake { throw quota() }
        val realB = GroqFake { emit("groq answer") }
        val sim = GeminiFake { emit("gemini must not be called") }
        val rReal = runner(FakeSource(mapOf(G to realA, R to realB), preferred = G))
        rReal.idle(); rReal.send("hello")
        val real = rReal.idle()
        val realTrace = traces.map { it.stage to it.result }
        traces.clear()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val storage2 = FileChatStorage(Files.createTempDirectory("fo2").toFile())
        val ledger2 = newLedger { now }
        val rSim = ChatRunner(
            storage2, ChatEngine(storage2), scope2, Dispatchers.IO,
            FakeSource(mapOf(G to sim, R to realB), preferred = G).also { it.ledger = ledger2 }, ledger2, net,
            clock = { now }, countdownSeconds = 2, tickMs = 20,
            trace = object : TraceSink { override fun record(event: TraceEvent) { traces += event } },
        )
        rSim.idle(); rSim.armSimulation(); rSim.send("hello")
        val simulated = rSim.idle()
        scope2.cancel()

        assertEquals(0, sim.calls.get()) // the simulation replaced the first attempt only; no real Gemini call
        assertEquals(2, realB.calls.get()) // Groq really answered both runs
        assertEquals(real.conversation.messages.map { it.text }, simulated.conversation.messages.map { it.text })
        assertEquals(real.temporaryProvider, simulated.temporaryProvider)
        assertEquals(real.dispatch!!.adapter, simulated.dispatch!!.adapter) // retry went to the Groq adapter both times
        assertEquals(R.label, simulated.conversation.messages.last().provider)
        // The simulated run adds one clearly labelled line for the fake first attempt; everything else is identical.
        assertEquals(1, traces.count { it.adapter == "SimulatedQuotaProvider" })
        assertEquals(realTrace.map { it.first }, traces.filter { it.adapter != "SimulatedQuotaProvider" }.map { it.stage })
        assertTrue(ledger2.isExhausted(G)) // and it was recorded as exhausted just like a real error
    }

    @Test fun simulatedFirstAttemptIsShownAsSimulatedAndRetryAsConfirmedGroq() = runBlocking {
        val a = GeminiFake { emit("x") }
        val b = GroqFake { delay(300); emit("groq answer") }
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 1)
        r.idle(); r.armSimulation(); r.send("hello")
        val first = r.until { it.dispatch?.simulated == true }.dispatch!!
        assertEquals("SimulatedQuotaProvider", first.adapter)
        assertFalse(first.mismatch)
        val second = r.until { it.dispatch?.adapter == "GroqFake" }.dispatch!!
        assertFalse(second.simulated)
        assertEquals(G, second.requested)
        assertEquals(R, r.idle().temporaryProvider)
    }

    // ------------------------------------------------------------------ trace content

    @Test fun traceRecordsDecisionAndAttemptWithoutAnySecretOrChatText() = runBlocking {
        val a = GeminiFake { throw quota() }
        val b = GroqFake { emit("secret reply text") }
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("my private question")
        r.idle()
        val decision = traces.first { it.stage.startsWith("decision") }
        assertEquals(G, decision.originalProvider)
        assertEquals("model-${G.name}", decision.originalModel)
        assertEquals("QuotaExhausted", decision.classification)
        assertEquals(R, decision.fallbackProvider)
        assertEquals("model-${R.name}", decision.fallbackModel)
        val attempt = traces.first { it.stage == "attempt finished" }
        assertEquals("GroqFake", attempt.adapter)
        assertTrue(attempt.streamStarted)
        assertTrue(attempt.responseReceived)
        assertEquals("success", attempt.result)
        val all = traces.joinToString { it.toString() }
        assertFalse(all.contains("KEY"))
        assertFalse(all.contains("private question"))
        assertFalse(all.contains("secret reply"))
    }

    // ------------------------------------------------------------------ OpenRouter in the chain

    private class OpenRouterFake(block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) : Fake(block)

    @Test fun chainGeminiThenGroqThenOpenRouterEachCalledOnceWithoutDuplicates() = runBlocking {
        val O = AiProvider.OPENROUTER
        val a = GeminiFake { throw quota() }
        val b = GroqFake { throw quota() }
        val c = OpenRouterFake { emit("openrouter answer") }
        val r = runner(FakeSource(mapOf(G to a, R to b, O to c), preferred = G))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(1, b.calls.get())
        assertEquals(1, c.calls.get())
        assertEquals(listOf("hello", "openrouter answer"), s.conversation.messages.map { it.text })
        assertEquals(O.label, s.conversation.messages.last().provider)
        assertEquals("model-${O.name}", c.requests.single().model)
        assertEquals(O, s.temporaryProvider)
        assertEquals(G, s.preferredProvider)
        assertEquals("OpenRouterFake", s.dispatch!!.adapter)
        assertFalse(s.dispatch!!.mismatch)
    }

    @Test fun openRouterDailyQuotaFailsOverToGroqAndRecordsDocumentedReset() = runBlocking {
        val O = AiProvider.OPENROUTER
        val a = OpenRouterFake { throw fail(LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 3_600_000), "free-models-per-day", ResetSource.DOCUMENTED)) }
        val b = GroqFake { emit("groq answer") }
        val r = runner(FakeSource(mapOf(O to a, R to b), preferred = O))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(R, s.temporaryProvider)
        assertEquals(ResetSource.DOCUMENTED, ledger.record(O)!!.source)
    }

    // ------------------------------------------------------------------ Cloudflare in the chain

    private class CloudflareFake(block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) : Fake(block)

    @Test fun cloudflareDailyQuotaFailsOverToGroqAndRecordsADocumentedReset() = runBlocking {
        val C = AiProvider.CLOUDFLARE
        val a = CloudflareFake { throw fail(LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 3_600_000), "free-neurons-per-day", ResetSource.DOCUMENTED)) }
        val b = GroqFake { emit("groq answer") }
        val r = runner(FakeSource(mapOf(C to a, R to b), preferred = C))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(R, s.temporaryProvider)
        assertEquals(ResetSource.DOCUMENTED, ledger.record(C)!!.source)
        assertEquals(listOf("hello", "groq answer"), s.conversation.messages.map { it.text })
    }

    @Test fun geminiToCloudflareWhenEarlierProvidersAreDisabledAndPerMinuteLimitsNeverSwitch() = runBlocking {
        val C = AiProvider.CLOUDFLARE
        val a = GeminiFake { throw quota() }
        val c = CloudflareFake { emit("cf answer") }
        val r = runner(FakeSource(mapOf(G to a, C to c), preferred = G, disabled = setOf(R)))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(C, s.temporaryProvider)
        assertEquals("CloudflareFake", s.dispatch!!.adapter)
        assertEquals(C.label, s.conversation.messages.last().provider)

        val a2 = CloudflareFake { throw fail(LlmError.Unclear429(null)) }
        val g2 = GeminiFake { emit("must not be called") }
        val storage2 = FileChatStorage(Files.createTempDirectory("fo3").toFile())
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ledger2 = newLedger { now }
        val r2 = ChatRunner(
            storage2, ChatEngine(storage2), scope2, Dispatchers.IO,
            FakeSource(mapOf(C to a2, G to g2), preferred = C).also { it.ledger = ledger2 }, ledger2, net,
            clock = { now }, countdownSeconds = 2, tickMs = 20,
        )
        r2.idle(); r2.send("hi")
        val s2 = r2.idle()
        scope2.cancel()
        assertEquals(0, g2.calls.get())
        assertNull(s2.temporaryProvider)
        assertNotNull(s2.problem)
    }

    // ------------------------------------------------------------------ SambaNova in the chain

    private class SambaFake(block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) : Fake(block)

    private fun sambaFailure(status: Int, code: String, vararg h: Pair<String, String>): LlmException {
        val body = """{"error":{"message":"m","type":"t","code":"$code"}}"""
        val parsed = dev.jarvis.assistant.ai.SambaNovaParsing.parseFailure(status, body, h.toMap(), "m", Instant.ofEpochMilli(now))
        return fail(parsed.error)
    }

    @Test fun sambaNovaDailyQuotaFromRealHeadersFailsOverToGroqWithTheReportedReset() = runBlocking {
        val S = AiProvider.SAMBANOVA
        val a = SambaFake {
            throw sambaFailure(429, "insufficient_quota", "x-ratelimit-remaining-requests-day" to "0", "x-ratelimit-reset-requests-day" to "3600")
        }
        val b = GroqFake { emit("groq answer") }
        val r = runner(FakeSource(mapOf(S to a, R to b), preferred = S))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(R, s.temporaryProvider)
        assertEquals(ResetSource.REPORTED, ledger.record(S)!!.source)
        assertEquals(listOf("hello", "groq answer"), s.conversation.messages.map { it.text })
    }

    @Test fun groqQuotaFailsOverToSambaNovaAndTheRequestReallyReachesItsAdapter() = runBlocking {
        val S = AiProvider.SAMBANOVA
        val a = GroqFake { throw quota() }
        val c = SambaFake { emit("samba answer") }
        val r = runner(FakeSource(mapOf(R to a, S to c), preferred = R, disabled = setOf(G)))
        r.idle(); r.send("hello")
        val s = r.idle()
        assertEquals(S, s.temporaryProvider)
        assertEquals("SambaFake", s.dispatch!!.adapter)
        assertEquals(1, c.calls.get())
        assertEquals(S.label, s.conversation.messages.last().provider)
    }

    @Test fun sambaNovaPerMinuteAndUnclearLimitsNeverSwitchProviders() = runBlocking {
        val S = AiProvider.SAMBANOVA
        for (err in listOf(
            sambaFailure(429, "insufficient_quota", "x-ratelimit-remaining-requests" to "0", "x-ratelimit-reset-requests" to "5"),
            sambaFailure(429, "insufficient_quota"),
            sambaFailure(429, "queue_full"),
        )) {
            val g = GroqFake { emit("must not be called") }
            val storage2 = FileChatStorage(Files.createTempDirectory("fo4").toFile())
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ledger2 = newLedger { now }
            val r2 = ChatRunner(
                storage2, ChatEngine(storage2), scope2, Dispatchers.IO,
                FakeSource(mapOf(S to SambaFake { throw err }, R to g), preferred = S).also { it.ledger = ledger2 }, ledger2, net,
                clock = { now }, countdownSeconds = 2, tickMs = 20, maxRetries = 0,
            )
            r2.idle(); r2.send("hi")
            val s2 = r2.idle()
            scope2.cancel()
            assertEquals(0, g.calls.get())
            assertNull(s2.temporaryProvider)
            assertNotNull(s2.problem)
        }
    }
}
